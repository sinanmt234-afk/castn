package com.easycast.phone

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.*
import androidx.compose.runtime.mutableStateListOf
import com.easycast.shared.Proto
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket

object Notif {
    fun build(ctx: Context, text: String): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("easycast", "Easy Cast", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(ctx, "easycast").setContentTitle("EASY CAST").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(pi).setOngoing(true).build()
    }
}

/** Keeps the phone awake and Wi-Fi at full speed while a video is being cast. */
class KeepAliveService : Service() {
    private var wl: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val n = Notif.build(this, "Casting video to the TV")
        if (Build.VERSION.SDK_INT >= 29) startForeground(3, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(3, n)
        if (wl == null) {
            wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "easycast:cast").apply { acquire(6 * 60 * 60 * 1000L) }
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "easycast").apply { acquire() }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { try { wl?.release(); wifi?.release() } catch (_: Exception) {}; super.onDestroy() }
}

/** Tiny HTTP server (with Range support) that serves the chosen video (and optional subtitle file) to the TV. */
class FileServer(private val ctx: Context, private val uri: Uri) {
    private val ss = ServerSocket(0)
    @Volatile var subUri: Uri? = null
    val port get() = ss.localPort

    fun start() {
        Thread { while (!ss.isClosed) { try { val c = ss.accept(); Thread { handle(c) }.start() } catch (e: Exception) { break } } }.start()
    }
    fun stop() { try { ss.close() } catch (_: Exception) {} }

    private fun handle(c: Socket) {
        try {
            c.use {
                val r = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val first = r.readLine() ?: return
                val path = (first.split(" ").getOrNull(1) ?: "/video").substringBefore('?')
                var range: String? = null
                while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break; if (l.startsWith("Range:", true)) range = l.substring(6).trim() }
                val o = it.getOutputStream()
                if (path.startsWith("/sub")) {
                    val su = subUri
                    val bytes = if (su != null) ctx.contentResolver.openInputStream(su)?.use { s -> s.readBytes() } else null
                    if (bytes == null) { o.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); o.flush(); return }
                    val m = if (path.endsWith(".vtt")) "text/vtt" else "application/x-subrip"
                    o.write("HTTP/1.1 200 OK\r\nContent-Type: $m; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    if (!first.startsWith("HEAD")) o.write(bytes)
                    o.flush(); return
                }
                ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val size = pfd.statSize
                    var start = 0L; var end = size - 1
                    if (range != null && range!!.startsWith("bytes=")) {
                        val p = range!!.removePrefix("bytes=").split("-")
                        start = p[0].toLongOrNull() ?: 0L
                        end = p.getOrNull(1)?.toLongOrNull() ?: (size - 1)
                    }
                    if (end >= size) end = size - 1
                    val len = end - start + 1
                    val mime = ctx.contentResolver.getType(uri) ?: "video/mp4"
                    val sb = StringBuilder()
                    sb.append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    sb.append("Content-Type: $mime\r\nAccept-Ranges: bytes\r\nContent-Length: $len\r\n")
                    if (range != null) sb.append("Content-Range: bytes $start-$end/$size\r\n")
                    sb.append("Connection: close\r\n\r\n")
                    o.write(sb.toString().toByteArray())
                    if (first.startsWith("HEAD")) { o.flush(); return }
                    val fis = FileInputStream(pfd.fileDescriptor); fis.channel.position(start)
                    val buf = ByteArray(256 * 1024); var left = len
                    while (left > 0) { val n = fis.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n <= 0) break; o.write(buf, 0, n); left -= n }
                    o.flush()
                }
            }
        } catch (_: Exception) {}
    }
}

/** Finds Easy Cast receivers on the Wi-Fi (mDNS). */
class Discovery(ctx: Context) {
    private val app = ctx.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private var lock: WifiManager.MulticastLock? = null
    val devices = mutableStateListOf<TvDevice>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var active = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(i: NsdServiceInfo) { if (i.serviceName.startsWith(Proto.SERVICE_PREFIX)) main.post { queue.add(i); next() } }
        override fun onServiceLost(i: NsdServiceInfo) { main.post { devices.removeAll { it.name == i.serviceName.removePrefix(Proto.SERVICE_PREFIX) } } }
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, e: Int) {}
        override fun onStopDiscoveryFailed(t: String, e: Int) {}
    }

    private fun next() {
        if (resolving) return
        val i = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(i, object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, e: Int) { main.post { resolving = false; next() } }
            override fun onServiceResolved(s: NsdServiceInfo) {
                main.post {
                    val h = s.host
                    if (h is Inet4Address) {
                        val name = s.serviceName.removePrefix(Proto.SERVICE_PREFIX)
                        devices.removeAll { it.name == name }
                        devices.add(TvDevice(name, h.hostAddress ?: "", s.port))
                    }
                    resolving = false; next()
                }
            }
        })
    }

    fun start() {
        if (active) return
        active = true
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wm.createMulticastLock("easycast").apply { setReferenceCounted(false); acquire() }
        try { nsd.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) } catch (_: Exception) {}
    }
    fun stop() {
        if (!active) return
        active = false
        try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
        try { lock?.release() } catch (_: Exception) {}
    }
}
