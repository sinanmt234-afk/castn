package com.easycast.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

val BG = Color(0xFF0A0A0C)
val GOLD = Color(0xFFD4AF37)
val CREAM = Color(0xFFF2EDE4)
val MUTED = Color(0xFF8A8578)
val CARD = Color(0x14FFFFFF)
val EDGE = Color(0x33D4AF37)

class MainActivity : ComponentActivity() {
    private lateinit var discovery: Discovery

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { Cast.castVideo(this, it) } }
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        val dev = Cast.device
        if (r.resultCode == RESULT_OK && d != null && dev != null) {
            val i = Intent(this, MirrorService::class.java).putExtra("code", r.resultCode).putExtra("data", d)
                .putExtra("host", dev.host).putExtra("quality", Cast.quality.name)
            ContextCompat.startForegroundService(this, i)
        }
    }
    private val pickSub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { Cast.addSubtitles(this, it) } }
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { launchProjection() }

    private fun launchProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection.launch(mpm.createScreenCaptureIntent())
    }

    private fun beginMirror() {
        val need = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) launchProjection() else perms.launch(need.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        discovery = Discovery(this)
        setContent {
            EasyCastApp(
                devices = discovery.devices,
                onPick = { pickVideo.launch(arrayOf("video/*", "audio/*")) },
                onMirror = { beginMirror() },
                onPickSub = { pickSub.launch(arrayOf("*/*")) },
                onStopMirror = { stopService(Intent(this, MirrorService::class.java)) },
            )
        }
    }
    override fun onStart() { super.onStart(); discovery.start() }
    override fun onStop() { super.onStop(); discovery.stop() }
}

@Composable
fun EasyCastApp(devices: List<TvDevice>, onPick: () -> Unit, onPickSub: () -> Unit, onMirror: () -> Unit, onStopMirror: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dev = Cast.device
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BG, Color(0xFF16130B))))
            .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("EASY CAST", style = TextStyle(color = GOLD, fontSize = 30.sp, fontWeight = FontWeight.Light, letterSpacing = 8.sp, fontFamily = FontFamily.SansSerif))
        Box(Modifier.padding(top = 10.dp).width(48.dp).height(2.dp).background(GOLD))
        Text("Your screen. Their big screen.", color = MUTED, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 28.dp))

        if (dev == null) {
            Label("NEARBY SCREENS")
            if (devices.isEmpty()) Card { Text("Searching your Wi-Fi…\nOpen Easy Cast on your TV or projector.", color = MUTED, fontSize = 14.sp) }
            devices.forEach { d ->
                Card(Modifier.clickable { Cast.connect(ctx, d) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, color = CREAM, fontSize = 18.sp, fontWeight = FontWeight.Light)
                            Text(d.host, color = MUTED, fontSize = 12.sp)
                        }
                        Text("CONNECT", color = GOLD, fontSize = 12.sp, letterSpacing = 2.sp)
                    }
                }
            }
            if (Cast.status.isNotEmpty()) Text(Cast.status, color = GOLD, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.height(20.dp))
            Label("CONNECT BY IP")
            var ip by remember { mutableStateOf("") }
            Card {
                OutlinedTextField(
                    value = ip, onValueChange = { ip = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. 192.168.1.20", color = MUTED) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = CREAM, unfocusedTextColor = CREAM, focusedBorderColor = GOLD, unfocusedBorderColor = EDGE, cursorColor = GOLD),
                )
                Spacer(Modifier.height(12.dp))
                GoldButton("Connect") { if (ip.isNotBlank()) Cast.connect(ctx, TvDevice("TV", ip.trim())) }
            }
        } else {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF4CD27A)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(dev.name, color = CREAM, fontSize = 18.sp, fontWeight = FontWeight.Light)
                        Text("Connected", color = MUTED, fontSize = 12.sp)
                    }
                    Text("DISCONNECT", color = GOLD, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.clickable { Cast.disconnect(ctx) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Label("CAST")
            Card(Modifier.clickable { onPick() }) {
                Text("Cast Video", color = CREAM, fontSize = 20.sp, fontWeight = FontWeight.Light)
                Text("Original quality. Control it with your TV remote.", color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Card(Modifier.clickable { if (Cast.mirroring) onStopMirror() else onMirror() }) {
                Text(if (Cast.mirroring) "Stop Mirroring" else "Mirror Screen", color = if (Cast.mirroring) GOLD else CREAM, fontSize = 20.sp, fontWeight = FontWeight.Light)
                Text(if (Cast.mirroring) "Your screen is live on the TV." else "Apps, games and sound on the big screen.", color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Label("MIRROR QUALITY")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Quality.values().forEach { q ->
                    val sel = Cast.quality == q
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).border(1.dp, if (sel) GOLD else EDGE, RoundedCornerShape(50))
                            .background(if (sel) GOLD else Color.Transparent).clickable { Cast.quality = q }.padding(horizontal = 16.dp, vertical = 9.dp)
                    ) { Text(q.label, color = if (sel) BG else CREAM, fontSize = 13.sp) }
                }
            }
            Text("Applies the next time you start mirroring.", color = MUTED, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))

            val title = Cast.title
            if (title != null) {
                Spacer(Modifier.height(24.dp))
                Label("NOW PLAYING")
                Card {
                    Text(title, color = CREAM, fontSize = 17.sp, fontWeight = FontWeight.Light, maxLines = 2)
                    var drag by remember { mutableStateOf<Float?>(null) }
                    val frac = if (Cast.dur > 0) (Cast.pos.toFloat() / Cast.dur).coerceIn(0f, 1f) else 0f
                    Slider(
                        value = drag ?: frac, onValueChange = { drag = it },
                        onValueChangeFinished = { drag?.let { Cast.seek((it * Cast.dur).toLong()) }; drag = null },
                        colors = SliderDefaults.colors(thumbColor = GOLD, activeTrackColor = GOLD, inactiveTrackColor = Color(0x33FFFFFF)),
                    )
                    Row { Text(fmt(Cast.pos), color = MUTED, fontSize = 12.sp); Spacer(Modifier.weight(1f)); Text(fmt(Cast.dur), color = MUTED, fontSize = 12.sp) }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Text("−10s", color = CREAM, fontSize = 15.sp, modifier = Modifier.clickable { Cast.skip(-10_000) }.padding(12.dp))
                        Box(Modifier.size(64.dp).clip(CircleShape).background(GOLD).clickable { Cast.playPause() }, contentAlignment = Alignment.Center) {
                            Text(if (Cast.playing) "❚❚" else "▶", color = BG, fontSize = 20.sp)
                        }
                        Text("+10s", color = CREAM, fontSize = 15.sp, modifier = Modifier.clickable { Cast.skip(10_000) }.padding(12.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("VOL", color = MUTED, fontSize = 11.sp, letterSpacing = 2.sp)
                        Slider(
                            value = Cast.volume, onValueChange = { Cast.changeVolume(it) }, valueRange = 0f..100f, modifier = Modifier.weight(1f).padding(start = 8.dp),
                            colors = SliderDefaults.colors(thumbColor = GOLD, activeTrackColor = GOLD, inactiveTrackColor = Color(0x33FFFFFF)),
                        )
                    }
                    if (Cast.audioTracks.size > 1) {
                        Spacer(Modifier.height(14.dp)); Label("AUDIO")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Cast.audioTracks.forEach { t -> Chip(t.label, t.on) { Cast.pickAudio(t.id) } }
                        }
                    }
                    Spacer(Modifier.height(14.dp)); Label("SUBTITLES")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Off", Cast.textTracks.none { it.on }) { Cast.pickText("off") }
                        Cast.textTracks.forEach { t -> Chip(t.label, t.on) { Cast.pickText(t.id) } }
                        Chip("+ File", false) { onPickSub() }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("STOP", color = GOLD, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.clickable { Cast.stopVideo(ctx) }.padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable private fun Chip(t: String, sel: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).border(1.dp, if (sel) GOLD else EDGE, RoundedCornerShape(50))
            .background(if (sel) GOLD else Color.Transparent).clickable { onClick() }.padding(horizontal = 16.dp, vertical = 9.dp)
    ) { Text(t, color = if (sel) BG else CREAM, fontSize = 13.sp, maxLines = 1) }
}

private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

@Composable private fun Label(t: String) = Text(t, color = MUTED, fontSize = 11.sp, letterSpacing = 3.sp, modifier = Modifier.padding(bottom = 10.dp))

@Composable private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(20.dp)).background(CARD).border(1.dp, EDGE, RoundedCornerShape(20.dp)).padding(20.dp), content = content)
}

@Composable private fun GoldButton(t: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(GOLD).clickable { onClick() }.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
        Text(t.uppercase(), color = BG, fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Medium)
    }
}
