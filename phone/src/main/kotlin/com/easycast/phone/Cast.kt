package com.easycast.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.easycast.shared.Proto
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

enum class Quality(val label: String, val longSide: Int, val mbps: Int, val fps: Int) {
    AUTO("Auto", 1920, 14, 60), HD("720p", 1280, 8, 60), FHD("1080p", 1920, 16, 60), UHD("4K", 3840, 35, 30)
}

data class TvDevice(val name: String, val host: String, val port: Int = Proto.CONTROL_PORT)
data class Track(val id: String, val label: String, val on: Boolean)

object Cast {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var sock: Socket? = null
    private var wr: PrintWriter? = null
    private var server: FileServer? = null

    var device by mutableStateOf<TvDevice?>(null)
    var status by mutableStateOf("")
    var title by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    var pos by mutableStateOf(0L)
    var dur by mutableStateOf(0L)
    var volume by mutableStateOf(50f)
    var mirroring by mutableStateOf(false)
    var quality by mutableStateOf(Quality.AUTO)
    var audioTracks by mutableStateOf<List<Track>>(emptyList())
    var textTracks by mutableStateOf<List<Track>>(emptyList())

    fun connect(ctx: Context, d: TvDevice) {
        disconnect(ctx)
        status = "Connecting…"
        Thread {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(d.host, d.port), 4000)
                sock = s
                wr = PrintWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), true)
                main.post { device = d; status = "Connected" }
                send(JSONObject().put("cmd", "hello").put("name", Build.MODEL))
                s.getInputStream().bufferedReader().forEachLine { line ->
                    try {
                        val o = JSONObject(line)
                        if (o.optString("evt") == "tracks") {
                            val a = parseTracks(o.optJSONArray("audio")); val t = parseTracks(o.optJSONArray("text"))
                            main.post { audioTracks = a; textTracks = t }
                        }
                        if (o.optString("evt") == "state") main.post {
                            playing = o.optBoolean("playing") && !o.optBoolean("ended")
                            pos = o.optLong("pos"); dur = o.optLong("dur")
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            main.post {
                if (sock === s) { cleanup(ctx); status = "Disconnected from TV" }
                else if (device == null && status == "Connecting…") status = "Couldn't reach the TV. Make sure both are on the same Wi-Fi."
            }
        }.start()
    }

    fun disconnect(ctx: Context) {
        val s = sock; sock = null
        try { s?.close() } catch (_: Exception) {}
        cleanup(ctx); status = ""
    }

    private fun cleanup(ctx: Context) {
        sock = null; wr = null
        device = null; title = null; playing = false; pos = 0; dur = 0
        audioTracks = emptyList(); textTracks = emptyList()
        server?.stop(); server = null
        ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        ctx.stopService(Intent(ctx, MirrorService::class.java))
    }

    fun send(o: JSONObject) { val w = wr ?: return; io.execute { try { w.println(o.toString()) } catch (_: Exception) {} } }

    fun castVideo(ctx: Context, uri: Uri) {
        val ip = sock?.localAddress?.hostAddress ?: return
        var name = "Video"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i) }
        }
        server?.stop()
        val srv = FileServer(ctx.applicationContext, uri); srv.start(); server = srv
        ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java))
        title = name; playing = true; pos = 0; dur = 0
        audioTracks = emptyList(); textTracks = emptyList()
        send(JSONObject().put("cmd", "load").put("url", "http://$ip:${srv.port}/video").put("title", name))
    }

    fun playPause() { playing = !playing; send(JSONObject().put("cmd", if (playing) "play" else "pause")) }
    fun seek(ms: Long) { pos = ms; send(JSONObject().put("cmd", "seek").put("ms", ms)) }
    fun skip(deltaMs: Long) = seek((pos + deltaMs).coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE))
    fun changeVolume(v: Float) { volume = v; send(JSONObject().put("cmd", "volume").put("value", v.toInt())) }
    private fun parseTracks(a: org.json.JSONArray?): List<Track> {
        val l = ArrayList<Track>()
        if (a != null) for (i in 0 until a.length()) { val t = a.getJSONObject(i); l.add(Track(t.getString("id"), t.optString("label", "Track"), t.optBoolean("on"))) }
        return l
    }

    fun pickAudio(id: String) { audioTracks = audioTracks.map { it.copy(on = it.id == id) }; send(JSONObject().put("cmd", "audio").put("id", id)) }
    fun pickText(id: String) { textTracks = textTracks.map { it.copy(on = it.id == id) }; send(JSONObject().put("cmd", "text").put("id", id)) }

    fun addSubtitles(ctx: Context, uri: Uri) {
        val srv = server ?: return
        val ip = sock?.localAddress?.hostAddress ?: return
        var name = "Subtitles"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i) }
        }
        val ext = if (name.lowercase().endsWith(".vtt")) "vtt" else "srt"
        srv.subUri = uri
        send(JSONObject().put("cmd", "addsub").put("url", "http://$ip:${srv.port}/sub.$ext").put("ext", ext).put("title", name.substringBeforeLast('.')))
    }

    fun stopVideo(ctx: Context) {
        send(JSONObject().put("cmd", "stop")); title = null; playing = false
        audioTracks = emptyList(); textTracks = emptyList()
        server?.stop(); server = null
        ctx.stopService(Intent(ctx, KeepAliveService::class.java))
    }
}
