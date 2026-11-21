package com.easycast.tv

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.*
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.*
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.easycast.shared.Proto
import org.json.JSONArray
import org.json.JSONObject
import android.net.Uri
import java.util.Locale
import java.io.*
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors

private val GOLD = Color.parseColor("#D4AF37")
private val CREAM = Color.parseColor("#F2EDE4")
private val MUTED = Color.parseColor("#8A8578")

class TvActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var alive = true
    @Volatile private var out: PrintWriter? = null
    private var client: Socket? = null
    private var mirrorSock: Socket? = null
    private var ctrlServer: ServerSocket? = null
    private var mirrorServer: ServerSocket? = null
    private var player: ExoPlayer? = null
    private var nsd: NsdManager? = null
    private var reg: NsdManager.RegistrationListener? = null
    private var mcLock: WifiManager.MulticastLock? = null
    private var curUrl: String? = null
    private val subs = mutableListOf<MediaItem.SubtitleConfiguration>()

    private lateinit var surface: SurfaceView
    private lateinit var playerView: PlayerView
    private lateinit var idle: View
    private lateinit var badge: TextView
    private lateinit var infoText: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float, color: Int, light: Boolean = true, spacing: Float = 0f) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); letterSpacing = spacing; gravity = Gravity.CENTER
        typeface = Typeface.create(if (light) "sans-serif-light" else "sans-serif", Typeface.NORMAL)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        playerView = PlayerView(this).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        infoText = tv("", 15f, MUTED)
        idle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#0A0A0C"), Color.parseColor("#1A160B")))
            addView(tv("EASY CAST", 46f, GOLD, true, 0.35f))
            addView(View(context).apply { setBackgroundColor(GOLD) }, LinearLayout.LayoutParams(dp(64), dp(2)).apply { topMargin = dp(18); bottomMargin = dp(22) })
            addView(tv("Ready to cast", 22f, CREAM))
            addView(infoText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
            addView(tv("Open Easy Cast on your phone and choose this screen", 14f, MUTED), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(36) })
        }
        badge = tv("", 16f, GOLD, false, 0.2f).apply {
            visibility = View.GONE; setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply { setColor(Color.parseColor("#CC0A0A0C")); cornerRadius = dp(30).toFloat(); setStroke(dp(1), Color.parseColor("#66D4AF37")) }
        }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        root.addView(idle, FrameLayout.LayoutParams(-1, -1))
        root.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(32); rightMargin = dp(40) })
        setContentView(root)

        infoText.text = "${Build.MODEL}  ·  ${localIp()}"

        player = ExoPlayer.Builder(this).build().also { p ->
            playerView.player = p
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { refreshBadge(); sendState() }
                override fun onPlaybackStateChanged(s: Int) { refreshBadge(); sendState() }
                override fun onTracksChanged(tracks: Tracks) { sendTracks() }
                override fun onPlayerError(e: PlaybackException) { infoText.text = "Couldn't play this video"; sendState() }
            })
        }
        tick()
        startControlServer(); startMirrorServer(); registerNsd()
    }

    private fun tick() { if (!alive) return; sendState(); main.postDelayed({ tick() }, 500) }

    private fun localIp(): String {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) for (a in ni.inetAddresses)
                if (!a.isLoopbackAddress && a is java.net.Inet4Address) return a.hostAddress ?: ""
        } catch (_: Exception) {}
        return "no network"
    }

    // ---------- control channel ----------
    private fun startControlServer() {
        Thread {
            try {
                val ss = ServerSocket(Proto.CONTROL_PORT).also { it.reuseAddress = true; ctrlServer = it }
                while (alive) {
                    val c = ss.accept(); c.tcpNoDelay = true
                    try { client?.close() } catch (_: Exception) {}
                    client = c
                    Thread { serve(c) }.start()
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun serve(c: Socket) {
        try {
            out = PrintWriter(OutputStreamWriter(c.getOutputStream(), Charsets.UTF_8), true)
            c.getInputStream().bufferedReader().forEachLine { line ->
                try { val o = JSONObject(line); main.post { handle(o) } } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        if (client === c) { out = null; main.post { showIdle(); player?.stop(); player?.clearMediaItems() } }
    }

    private fun handle(o: JSONObject) {
        val p = player ?: return
        when (o.optString("cmd")) {
            "load" -> {
                endMirror()
                playerView.visibility = View.VISIBLE; idle.visibility = View.GONE
                curUrl = o.getString("url"); subs.clear()
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverrides().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
                p.setMediaItem(MediaItem.fromUri(curUrl!!)); p.prepare(); p.playWhenReady = true
            }
            "play" -> p.play()
            "pause" -> p.pause()
            "seek" -> p.seekTo(o.optLong("ms"))
            "audio" -> selectTrack(C.TRACK_TYPE_AUDIO, o.optString("id"))
            "text" -> {
                val id = o.optString("id")
                if (id == "off") p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                else selectTrack(C.TRACK_TYPE_TEXT, id)
            }
            "addsub" -> {
                val url = curUrl ?: return
                val mime = if (o.optString("ext") == "vtt") MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
                subs.clear()
                subs.add(MediaItem.SubtitleConfiguration.Builder(Uri.parse(o.getString("url"))).setMimeType(mime)
                    .setLabel(o.optString("title", "Subtitles")).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build())
                val pos = p.currentPosition; val was = p.playWhenReady
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
                p.setMediaItem(MediaItem.Builder().setUri(url).setSubtitleConfigurations(subs.toList()).build(), pos)
                p.prepare(); p.playWhenReady = was
            }
            "volume" -> {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * o.optInt("value") / 100, 0)
            }
            "stop" -> { p.stop(); p.clearMediaItems(); showIdle() }
        }
    }

    private fun selectTrack(type: Int, id: String) {
        val p = player ?: return
        val parts = id.split(":")
        val gi = parts.getOrNull(0)?.toIntOrNull() ?: return
        val ti = parts.getOrNull(1)?.toIntOrNull() ?: return
        val g = p.currentTracks.groups.getOrNull(gi) ?: return
        if (g.type != type) return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, listOf(ti))).build()
    }

    private fun trackLabel(f: androidx.media3.common.Format, audio: Boolean, n: Int): String {
        var s = f.label?.takeIf { it.isNotBlank() }
            ?: f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage.takeIf { d -> d.isNotBlank() } }
            ?: "Track ${n + 1}"
        if (audio && f.channelCount > 2) s += if (f.channelCount == 6) " 5.1" else " ${f.channelCount}ch"
        return s
    }

    private fun sendTracks() {
        val p = player ?: return
        fun list(type: Int): JSONArray {
            val a = JSONArray(); var n = 0
            p.currentTracks.groups.forEachIndexed { gi, g ->
                if (g.type == type) for (i in 0 until g.length) {
                    if (!g.isTrackSupported(i)) continue
                    a.put(JSONObject().put("id", "$gi:$i").put("label", trackLabel(g.getTrackFormat(i), type == C.TRACK_TYPE_AUDIO, n++)).put("on", g.isTrackSelected(i)))
                }
            }
            return a
        }
        val o = JSONObject().put("evt", "tracks").put("audio", list(C.TRACK_TYPE_AUDIO)).put("text", list(C.TRACK_TYPE_TEXT))
        val w = out ?: return
        io.execute { try { w.println(o.toString()) } catch (_: Exception) {} }
    }

    private fun showIdle() { playerView.visibility = View.GONE; idle.visibility = View.VISIBLE; badge.visibility = View.GONE; infoText.text = "${Build.MODEL}  ·  ${localIp()}" }

    private fun refreshBadge() {
        val p = player ?: return
        if (playerView.visibility == View.VISIBLE && p.playbackState == Player.STATE_READY && !p.playWhenReady) { badge.text = "❚❚  PAUSED"; badge.visibility = View.VISIBLE }
        else badge.visibility = View.GONE
    }

    private fun sendState() {
        val p = player ?: return
        if (playerView.visibility != View.VISIBLE) return
        val o = JSONObject().put("evt", "state")
            .put("playing", p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING))
            .put("pos", p.currentPosition).put("dur", p.duration.coerceAtLeast(0L))
            .put("ended", p.playbackState == Player.STATE_ENDED)
        val w = out ?: return
        io.execute { try { w.println(o.toString()) } catch (_: Exception) {} }
    }

    // ---------- TV remote ----------
    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        val p = player
        if (mirrorSock != null) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MEDIA_STOP) { try { mirrorSock?.close() } catch (_: Exception) {}; return true }
            return super.onKeyDown(k, e)
        }
        if (p != null && playerView.visibility == View.VISIBLE) {
            when (k) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { p.playWhenReady = !p.playWhenReady; return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { p.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.pause(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { p.seekTo(p.currentPosition + 10_000); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); return true }
                KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_BACK -> { p.stop(); p.clearMediaItems(); showIdle(); out?.let { w -> io.execute { w.println(JSONObject().put("evt", "state").put("playing", false).put("ended", true)) } }; return true }
            }
        }
        return super.onKeyDown(k, e)
    }

    // ---------- screen mirroring ----------
    private fun startMirrorServer() {
        Thread {
            try {
                val ss = ServerSocket(Proto.MIRROR_PORT).also { it.reuseAddress = true; mirrorServer = it }
                while (alive) {
                    val c = ss.accept(); c.tcpNoDelay = true
                    try { mirrorSock?.close() } catch (_: Exception) {}
                    mirrorSock = c
                    Thread { receiveMirror(c) }.start()
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun endMirror() { try { mirrorSock?.close() } catch (_: Exception) {} }

    private fun receiveMirror(c: Socket) {
        var vdec: MediaCodec? = null; var adec: MediaCodec? = null; var track: AudioTrack? = null
        var w = 1920; var h = 1080
        val info = MediaCodec.BufferInfo()
        try {
            val din = DataInputStream(BufferedInputStream(c.getInputStream(), 1 shl 16))
            while (true) {
                val type = din.readUnsignedByte(); val pts = din.readLong(); val len = din.readInt()
                val b = ByteArray(len); din.readFully(b)
                when (type) {
                    Proto.PKT_HEADER -> {
                        val bb = ByteBuffer.wrap(b); w = bb.int; h = bb.int
                        main.post { player?.stop(); player?.clearMediaItems(); playerView.visibility = View.GONE; idle.visibility = View.GONE; badge.visibility = View.GONE }
                        var t = 0; while (!surface.holder.surface.isValid && t < 60) { Thread.sleep(50); t++ }
                    }
                    Proto.PKT_VCONFIG -> {
                        val f = MediaFormat.createVideoFormat("video/avc", w, h)
                        f.setByteBuffer("csd-0", ByteBuffer.wrap(b))
                        if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                        vdec = MediaCodec.createDecoderByType("video/avc").also { it.configure(f, surface.holder.surface, null, 0); it.start() }
                    }
                    Proto.PKT_VFRAME -> vdec?.let { d ->
                        val i = d.dequeueInputBuffer(20_000)
                        if (i >= 0) { d.getInputBuffer(i)!!.apply { clear(); put(b) }; d.queueInputBuffer(i, 0, b.size, pts, 0) }
                        while (true) { val o = d.dequeueOutputBuffer(info, 0); if (o >= 0) d.releaseOutputBuffer(o, true) else break }
                    }
                    Proto.PKT_ACONFIG -> {
                        val f = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2)
                        f.setByteBuffer("csd-0", ByteBuffer.wrap(b))
                        adec = MediaCodec.createDecoderByType("audio/mp4a-latm").also { it.configure(f, null, null, 0); it.start() }
                        val min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                        track = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                            .setBufferSizeInBytes(min * 4).setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
                    }
                    Proto.PKT_AFRAME -> adec?.let { d ->
                        val i = d.dequeueInputBuffer(20_000)
                        if (i >= 0) { d.getInputBuffer(i)!!.apply { clear(); put(b) }; d.queueInputBuffer(i, 0, b.size, pts, 0) }
                        while (true) {
                            val o = d.dequeueOutputBuffer(info, 0)
                            if (o < 0) break
                            val ob = d.getOutputBuffer(o)!!
                            val pcm = ByteArray(info.size); ob.position(info.offset); ob.get(pcm)
                            track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
                            d.releaseOutputBuffer(o, false)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        try { vdec?.stop(); vdec?.release() } catch (_: Exception) {}
        try { adec?.stop(); adec?.release() } catch (_: Exception) {}
        try { track?.stop(); track?.release() } catch (_: Exception) {}
        try { c.close() } catch (_: Exception) {}
        if (mirrorSock === c) { mirrorSock = null; main.post { showIdle() } }
    }

    // ---------- discovery ----------
    private fun registerNsd() {
        try {
            mcLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createMulticastLock("easycast").apply { setReferenceCounted(false); acquire() }
            val m = getSystemService(Context.NSD_SERVICE) as NsdManager; nsd = m
            val info = NsdServiceInfo().apply { serviceName = Proto.SERVICE_PREFIX + Build.MODEL; serviceType = Proto.SERVICE_TYPE; port = Proto.CONTROL_PORT }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            reg = l; m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        alive = false
        try { reg?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        try { mcLock?.release() } catch (_: Exception) {}
        for (s in listOf<Closeable?>(client, mirrorSock, ctrlServer, mirrorServer)) try { s?.close() } catch (_: Exception) {}
        player?.release(); io.shutdownNow()
        super.onDestroy()
    }
}
