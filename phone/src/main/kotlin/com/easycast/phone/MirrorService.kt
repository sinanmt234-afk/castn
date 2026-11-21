package com.easycast.phone

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.*
import androidx.annotation.RequiresApi
import com.easycast.shared.Proto
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/** Mirrors the phone screen (and its sound) to the TV as H.264 + AAC over the local network. */
class MirrorService : Service() {
    @Volatile private var running = true
    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var venc: MediaCodec? = null
    private var aenc: MediaCodec? = null
    private var sock: Socket? = null
    private var out: DataOutputStream? = null
    private var wl: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i == null) { stopSelf(); return START_NOT_STICKY }
        val n = Notif.build(this, "Mirroring your screen to the TV")
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(2, n)
        val code = i.getIntExtra("code", 0)
        @Suppress("DEPRECATION") val data = i.getParcelableExtra<Intent>("data")
        val host = i.getStringExtra("host") ?: ""
        val q = Quality.values().firstOrNull { it.name == i.getStringExtra("quality") } ?: Quality.AUTO
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        main.post { Cast.mirroring = true }
        Thread {
            try { stream(code, data, host, q) } catch (_: Exception) {}
            finally { release(); main.post { Cast.mirroring = false; stopSelf() } }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() { running = false; release(); super.onDestroy() }

    private fun makeFormat(w: Int, h: Int, q: Quality) = MediaFormat.createVideoFormat("video/avc", w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, q.mbps * 1_000_000)
        setInteger(MediaFormat.KEY_FRAME_RATE, q.fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        setInteger(MediaFormat.KEY_PRIORITY, 0)
        if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
    }

    private fun stream(code: Int, data: Intent, host: String, q: Quality) {
        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "easycast:mirror").apply { acquire(4 * 60 * 60 * 1000L) }
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "easycast").apply { acquire() }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data) ?: return
        proj = p
        p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { running = false } }, main)

        val s = Socket(); s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, Proto.MIRROR_PORT), 4000)
        sock = s; out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 1 shl 16))

        val dm = resources.displayMetrics
        val sw = dm.widthPixels; val sh = dm.heightPixels
        var w = 0; var h = 0
        for (ls in listOf(q.longSide, 1920, 1280).distinct()) {
            val scale = minOf(1f, ls.toFloat() / maxOf(sw, sh))
            w = (sw * scale).toInt() and 15.inv(); h = (sh * scale).toInt() and 15.inv()
            try {
                val e = MediaCodec.createEncoderByType("video/avc")
                venc = e
                e.configure(makeFormat(w, h, q), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                break
            } catch (_: Exception) { try { venc?.release() } catch (_: Exception) {}; venc = null }
        }
        val enc = venc ?: return
        val surface = enc.createInputSurface()
        enc.start()
        vd = p.createVirtualDisplay("EasyCast", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        send(Proto.PKT_HEADER, 0, ByteBuffer.allocate(8).putInt(w).putInt(h).array())

        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Thread { audioLoop(p) }.start()
        }

        val info = MediaCodec.BufferInfo()
        while (running) {
            val i = enc.dequeueOutputBuffer(info, 100_000)
            if (i >= 0) {
                val buf = enc.getOutputBuffer(i)!!
                val arr = ByteArray(info.size)
                buf.position(info.offset); buf.get(arr)
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                enc.releaseOutputBuffer(i, false)
                send(if (isConfig) Proto.PKT_VCONFIG else Proto.PKT_VFRAME, info.presentationTimeUs, arr)
            }
        }
    }

    private fun send(type: Int, pts: Long, b: ByteArray) {
        val o = out ?: return
        try {
            synchronized(o) { o.writeByte(type); o.writeLong(pts); o.writeInt(b.size); o.write(b); o.flush() }
        } catch (e: Exception) { running = false }
    }

    @RequiresApi(29)
    private fun audioLoop(p: MediaProjection) {
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val af = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            @Suppress("MissingPermission")
            val rec = AudioRecord.Builder().setAudioFormat(af).setBufferSizeInBytes(maxOf(min * 2, 16384)).setAudioPlaybackCaptureConfig(cfg).build()
            val enc = MediaCodec.createEncoderByType("audio/mp4a-latm")
            val f = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start(); aenc = enc
            rec.startRecording()
            val pcm = ByteArray(4096); val info = MediaCodec.BufferInfo()
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n > 0) {
                    val idx = enc.dequeueInputBuffer(10_000)
                    if (idx >= 0) { enc.getInputBuffer(idx)!!.apply { clear(); put(pcm, 0, n) }; enc.queueInputBuffer(idx, 0, n, System.nanoTime() / 1000, 0) }
                }
                while (true) {
                    val o = enc.dequeueOutputBuffer(info, 0)
                    if (o < 0) break
                    val bb = enc.getOutputBuffer(o)!!
                    val arr = ByteArray(info.size); bb.position(info.offset); bb.get(arr)
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    enc.releaseOutputBuffer(o, false)
                    send(if (isConfig) Proto.PKT_ACONFIG else Proto.PKT_AFRAME, info.presentationTimeUs, arr)
                }
            }
            rec.stop(); rec.release()
        } catch (_: Exception) {}
    }

    private fun release() {
        running = false
        try { vd?.release() } catch (_: Exception) {}; vd = null
        try { venc?.stop(); venc?.release() } catch (_: Exception) {}; venc = null
        try { aenc?.stop(); aenc?.release() } catch (_: Exception) {}; aenc = null
        try { proj?.stop() } catch (_: Exception) {}; proj = null
        try { sock?.close() } catch (_: Exception) {}; sock = null
        try { wl?.release() } catch (_: Exception) {}; wl = null
        try { wifi?.release() } catch (_: Exception) {}; wifi = null
    }
}
