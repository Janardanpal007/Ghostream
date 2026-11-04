package com.fastdownloader.app.ui

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.graphics.drawable.Icon
import com.fastdownloader.app.R
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fastdownloader.app.engine.Engine
import kotlinx.coroutines.delay

object VideoCtl {
    val FITS = listOf("original" to "Original", "fit" to "Fit", "fill" to "Fill", "zoom" to "Zoom")
    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    /** Video shape and speed, applied by the page script in every frame (see assets/page.js, part 6). */
    fun apply(web: WebView?, fit: String? = null, rate: Float? = null) {
        val parts = listOfNotNull(fit?.let { "fit:'$it'" }, rate?.let { "rate:$it" })
        web?.evaluateJavascript("window.postMessage({__fdVideo:{${parts.joinToString(",")}}},'*')", null)
    }

    /** "portrait" or "landscape" for the video now in full screen ("" when it's in a frame the page can't see into). */
    const val SHAPE = """(function(){var e=document.fullscreenElement||document.webkitFullscreenElement;
var v=e&&(e.tagName==='VIDEO'?e:e.querySelector('video'));
if(!v){var vs=document.getElementsByTagName('video');for(var i=0;i<vs.length;i++){if(!vs[i].paused){v=vs[i];break;}}}
return v&&v.videoWidth?(v.videoHeight>v.videoWidth?'portrait':'landscape'):'';})()"""

    /** The app as a small window that keeps playing the video. */
    fun enterPip(act: Activity, portrait: Boolean): Boolean {
        if (!act.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        return runCatching { act.enterPictureInPictureMode(pipParams(act, playing = true, portrait = portrait)) }.getOrDefault(false)
    }

    /** The window's shape (the video's own, when known) and its buttons: back 10 s, play or pause, forward 10 s. */
    fun pipParams(act: Activity, playing: Boolean, portrait: Boolean = this.portrait): PictureInPictureParams {
        fun action(cmd: String, icon: Int, title: String, code: Int) = RemoteAction(
            Icon.createWithResource(act, icon), title, title,
            PendingIntent.getBroadcast(
                act, code, Intent(PIP_ACTION).setPackage(act.packageName).putExtra("cmd", cmd),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val shape = size ?: if (portrait) Rational(9, 16) else Rational(16, 9)
        return PictureInPictureParams.Builder()
            .setAspectRatio(shape)
            .setActions(
                listOf(
                    action("back", R.drawable.ic_pip_rewind, "Back 10 seconds", 1),
                    if (playing) action("pause", R.drawable.ic_pip_pause, "Pause", 2) else action("play", R.drawable.ic_pip_play, "Play", 2),
                    action("forward", R.drawable.ic_pip_forward, "Forward 10 seconds", 3),
                ),
            )
            .build()
    }

    const val PIP_ACTION = "com.fastdownloader.app.PIP_CONTROL"

    /** A picture-in-picture button pressed: play, pause or skip in the page (every frame; see assets/page.js, part 6). */
    fun command(web: WebView?, cmd: String) {
        val o = when (cmd) {
            "play", "pause" -> "cmd:'$cmd'"
            "back" -> "cmd:'seek',by:-10"
            "forward" -> "cmd:'seek',by:10"
            else -> return
        }
        web ?: return
        // Pages may only start sound after a tap on the page; a press on the window's button counts as one here.
        if (cmd == "play") {
            web.settings.mediaPlaybackRequiresUserGesture = false
            web.postDelayed({ web.settings.mediaPlaybackRequiresUserGesture = true }, 1500)
        }
        web.evaluateJavascript("window.postMessage({__fdVideo:{$o}},'*')", null)
    }

    /**
     * Going in or out of full screen moves the video to another view and usually turns the screen, and pages like
     * YouTube pause it while that happens (the screen stays black). A video that was playing is started again.
     */
    fun keepPlaying(web: WebView?) {
        web ?: return
        web.evaluateJavascript(SIZE) { playing ->
            if (playing.isNullOrEmpty() || playing == "\"\"") return@evaluateJavascript
            for (at in longArrayOf(700, 1600, 3000)) web.postDelayed({
                web.evaluateJavascript(PAUSED) { paused -> if (paused == "true") command(web, "play") }
            }, at)
        }
    }

    private const val PAUSED = """(function(){var vs=document.getElementsByTagName('video');for(var i=0;i<vs.length;i++){var v=vs[i];
if(v.videoWidth&&v.paused&&!v.ended&&v.currentTime>0)return true;}return false;})()"""

    /** The playing video's size as "width:height" ("" when there's none the page can see). */
    const val SIZE = """(function(){var vs=document.getElementsByTagName('video');for(var i=0;i<vs.length;i++){var v=vs[i];
if(!v.paused&&v.videoWidth)return v.videoWidth+':'+v.videoHeight;}return '';})()"""

    /** The playing video's shape for the picture-in-picture window, within what Android allows (2.39:1 to 1:2.39). */
    @Volatile var size: Rational? = null

    fun sizeOf(result: String?): Rational? {
        val (w, h) = result?.trim('"')?.split(':')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 } ?: return null
        val r = w.toDouble() / h
        return when {
            r > 2.39 -> Rational(239, 100)
            r < 1 / 2.39 -> Rational(100, 239)
            else -> Rational(w, h)
        }
    }

    /** Set while a video plays full screen or fills the screen: whether it's upright, for picture-in-picture. */
    @Volatile var portrait = false
}

/**
 * A video the page put in full screen: drawn over the whole app, without status or navigation bars,
 * turned to match the video, with the browser's video controls on top.
 */
@Composable
fun FullScreenLayer() {
    val fs = BrowserChrome.fullScreen ?: return
    val ctx = LocalContext.current
    val act = ctx.findActivity() ?: return
    val web = BrowserChrome.activeWeb
    BackHandler { fs.second.onCustomViewHidden(); BrowserChrome.fullScreen = null }
    DisposableEffect(fs) {
        val settings = Engine.settings
        VideoCtl.keepPlaying(web)
        VideoCtl.apply(web, fit = settings.videoFit)
        val bars = WindowCompat.getInsetsController(act.window, act.window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        // Use the whole screen on every phone, including beside a notch or camera hole.
        val cutout = if (Build.VERSION.SDK_INT >= 28) act.window.attributes.layoutInDisplayCutoutMode else 0
        if (Build.VERSION.SDK_INT >= 28) {
            act.window.attributes = act.window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (settings.autoLandscape) act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Upright videos (shorts, reels) stay upright.
        web?.evaluateJavascript(VideoCtl.SHAPE) { shape ->
            VideoCtl.portrait = shape == "\"portrait\""
            if (BrowserChrome.fullScreen === fs && VideoCtl.portrait && settings.autoLandscape) {
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
        onDispose {
            VideoCtl.keepPlaying(web)
            bars.show(WindowInsetsCompat.Type.systemBars())
            // Back to the phone's own rotation setting (not to what was asked before: when a page goes full screen
            // twice in a row, that's still sideways, and with auto-rotate off the phone would stay sideways).
            if (BrowserChrome.fullScreen == null) act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (Build.VERSION.SDK_INT >= 28) act.window.attributes = act.window.attributes.apply { layoutInDisplayCutoutMode = cutout }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.runtime.key(fs) {
            AndroidView(factory = { fs.first.apply { (parent as? ViewGroup)?.removeView(this); setBackgroundColor(android.graphics.Color.BLACK) } }, modifier = Modifier.fillMaxSize())
        }
        if (!BrowserChrome.pip) VideoOverlay(web, act, onExit = { fs.second.onCustomViewHidden(); BrowserChrome.fullScreen = null })
    }
}

/**
 * Swipes over a video that fills the screen sideways (landscape): up and down on the right half sets the volume,
 * on the left half the brightness. Touches go through the activity first (see MainActivity.dispatchTouchEvent), so the page's player
 * keeps its own taps; a swipe, once it is clearly up or down, is taken from the page.
 */
object VideoSwipe {
    /** What's being set and its level (0 to 1), while a swipe is on and a moment after. */
    var level by mutableStateOf<Pair<String, Float>?>(null)
        private set

    /** Set while the video controls are locked: no swipes then either. */
    @Volatile var locked = false

    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    private var kind: String? = null
    private var base = 0f
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val hide = Runnable { level = null }

    fun active(act: Activity) = (BrowserChrome.fullScreen != null || BrowserChrome.videoMode) && !BrowserChrome.pip && !locked &&
        act.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /** True when the touch is a brightness or volume swipe, which the page then doesn't get ([passOn] gets a cancel). */
    fun handle(act: Activity, ev: android.view.MotionEvent, passOn: (android.view.MotionEvent) -> Boolean): Boolean {
        val w = act.window.decorView.width.toFloat()
        val h = act.window.decorView.height.toFloat()
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                startX = ev.x; startY = ev.y; kind = null
                // Clear of the edges, where the system's own swipes and the page's control rows are.
                tracking = active(act) && h > 0 && ev.y > h * 0.12f && ev.y < h * 0.9f
                return false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val k = kind
                if (k != null) {
                    set(act, k, (base + (startY - ev.y) / (h * 0.6f)).coerceIn(0f, 1f))
                    return true
                }
                val dx = kotlin.math.abs(ev.x - startX)
                val dy = kotlin.math.abs(ev.y - startY)
                val slop = 24 * act.resources.displayMetrics.density
                if (!tracking || ev.pointerCount > 1) return false
                // Sideways (seeking) or slanted: the page's own gesture, from here on.
                if ((dx > slop && dx > dy) || (dy >= slop && dy < dx * 2)) { tracking = false; return false }
                // Not decided yet: the page doesn't see the finger move, or YouTube would take an upward swipe
                // for its own (the "more videos" panel). A tap barely moves, so taps still work.
                if (dy < slop) return true
                val next = if (startX > w / 2) "volume" else "brightness"
                kind = next
                base = current(act, next)
                startY = ev.y
                main.removeCallbacks(hide)
                level = next to base
                val cancel = android.view.MotionEvent.obtain(ev).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                passOn(cancel)
                cancel.recycle()
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                tracking = false
                if (kind == null) return false
                kind = null
                main.postDelayed(hide, 700)
                return true
            }
            else -> return kind != null
        }
    }

    private fun current(act: Activity, kind: String): Float = if (kind == "brightness") {
        act.window.attributes.screenBrightness.takeIf { it >= 0 }
            ?: (runCatching { android.provider.Settings.System.getInt(act.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) }
                .getOrDefault(128) / 255f)
    } else {
        val am = act.getSystemService(android.media.AudioManager::class.java)
        am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
    }

    private fun set(act: Activity, kind: String, v: Float) {
        if (kind == "brightness") {
            act.window.attributes = act.window.attributes.apply { screenBrightness = v.coerceAtLeast(0.01f) }
        } else {
            val am = act.getSystemService(android.media.AudioManager::class.java)
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            runCatching { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, kotlin.math.round(v * max).toInt(), 0) }
        }
        level = kind to v
    }

    /** Back to the phone's own brightness once the video no longer fills the screen. */
    fun reset(act: Activity) {
        act.window.attributes = act.window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        level = null
    }
}

/** The level being swiped, as an upright bar on its own side: volume on the right, brightness on the left. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SwipeLevelBar() {
    val (kind, v) = VideoSwipe.level ?: return
    val volume = kind == "volume"
    Column(
        Modifier.align(if (volume) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 32.dp)
            .clip(Shape.pill).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${kotlin.math.round(v * 100).toInt()}", style = Type.label, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.width(6.dp).height(150.dp).clip(Shape.pill).background(Color.White.copy(alpha = 0.3f)), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(v).clip(Shape.pill).background(Color.White))
        }
        Spacer(Modifier.height(10.dp))
        Icon(if (volume) Icons.Volume else Icons.Brightness, if (volume) "Volume" else "Brightness", tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/**
 * The browser's own controls over a playing video: shape (Original, Fit, Fill, Zoom), speed, rotation,
 * picture-in-picture and a touch lock. They sit in a thin row at the top, so the page's player keeps its controls.
 */
@Composable
fun VideoOverlay(web: WebView?, act: Activity, onExit: (() -> Unit)?) {
    var shown by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var lockHint by remember { mutableIntStateOf(0) }
    var fit by remember { mutableStateOf(Engine.settings.videoFit) }
    var rate by remember { mutableFloatStateOf(1f) }
    var menu by remember { mutableStateOf<String?>(null) }
    var touched by remember { mutableIntStateOf(0) }

    LaunchedEffect(shown, touched, menu) {
        if (shown && menu == null) { delay(4000); shown = false }
    }
    LaunchedEffect(lockHint) { if (lockHint > 0) { delay(2500); lockHint = 0 } }
    SideEffect { VideoSwipe.locked = locked }
    DisposableEffect(act) { onDispose { VideoSwipe.locked = false; VideoSwipe.reset(act) } }

    if (locked) {
        BackHandler { lockHint++ }
        // Swallows every touch, so a pocket or a child can't pause or skip. The lock button unlocks.
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { lockHint++ },
        ) {
            AnimatedVisibility(lockHint > 0, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).clickable { locked = false; shown = true },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.LockOpen, "Unlock", tint = Color.White, modifier = Modifier.size(28.dp)) }
                    Spacer(Modifier.height(8.dp))
                    Text("Tap to unlock", style = Type.small, color = Color.White)
                }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        SwipeLevelBar()
        if (!shown) {
            // A small handle on the left edge brings the controls back, clear of the players' own corner buttons.
            Box(
                Modifier.align(Alignment.CenterStart).padding(8.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
                    .clickable { shown = true; touched++ },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Tune, "Video controls", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp)) }
        }
        AnimatedVisibility(shown, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.padding(top = 12.dp).clip(Shape.pill).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (onExit != null) OverlayButton(Icons.Back, "Exit full screen") { onExit() }
                    OverlayButton(Icons.Lock, "Lock touch") { locked = true; lockHint = 1; menu = null }
                    OverlayButton(Icons.AspectRatio, "Shape: ${VideoCtl.FITS.first { it.first == fit }.second}", label = VideoCtl.FITS.first { it.first == fit }.second) {
                        menu = if (menu == "fit") null else "fit"; touched++
                    }
                    OverlayButton(Icons.Speed, "Speed", label = "${rate}x".replace(".0x", "x")) { menu = if (menu == "speed") null else "speed"; touched++ }
                    OverlayButton(Icons.Rotate, "Rotate") {
                        touched++
                        act.requestedOrientation = if (act.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    }
                    OverlayButton(Icons.Pip, "Picture-in-picture") { menu = null; VideoCtl.enterPip(act, VideoCtl.portrait) }
                    OverlayButton(Icons.Close, "Hide controls") { shown = false; menu = null }
                }
                when (menu) {
                    "fit" -> Choices(VideoCtl.FITS, fit) { id ->
                        fit = id
                        VideoCtl.apply(web, fit = id)
                        Engine.updateSettings { it.copy(videoFit = id) }
                        touched++
                    }
                    "speed" -> Choices(VideoCtl.SPEEDS.map { it.toString() to "${it}x".replace(".0x", "x") }, rate.toString()) { v ->
                        rate = v.toFloat()
                        VideoCtl.apply(web, rate = rate)
                        touched++
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayButton(icon: ImageVector, description: String, label: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).clip(Shape.pill).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(20.dp))
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            Text(label.uppercase(), style = Type.label, color = Color.White)
        }
    }
}

@Composable
private fun Choices(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(
        Modifier.padding(top = 8.dp).clip(Shape.pill).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (id, name) ->
            val on = id == selected
            Box(
                Modifier.height(36.dp).clip(Shape.pill).background(if (on) Color.White else Color.Transparent).clickable { onPick(id) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(name.uppercase(), style = Type.label, color = if (on) Color.Black else Color.White) }
        }
    }
}
