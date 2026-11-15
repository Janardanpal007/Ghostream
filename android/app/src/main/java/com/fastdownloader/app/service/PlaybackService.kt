package com.fastdownloader.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fastdownloader.app.R
import com.fastdownloader.app.engine.AdBlock
import com.fastdownloader.app.ui.BrowserChrome
import com.fastdownloader.app.ui.MainActivity
import com.fastdownloader.app.ui.VideoCtl

/**
 * Keeps a video or song from the Browser tab playing when the app leaves the screen or the screen turns off: a
 * foreground service, so Android doesn't freeze or end the app, with a media notification (also on the lock screen
 * and for headphone buttons) to play, pause or stop. Started when something starts playing; it ends itself once
 * nothing has played for a while. The page is kept from noticing it's hidden by assets/page.js (part 0b).
 */
class PlaybackService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var wake: PowerManager.WakeLock? = null
    private var playing = true
    private var pausedAt = 0L
    private var title = ""
    private val tick = object : Runnable {
        override fun run() {
            follow()
            main.postDelayed(this, CHECK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        pausedAt = System.currentTimeMillis()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Playing in the background", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        session = MediaSession(this, "Ghostream").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = control(true)
                override fun onPause() = control(false)
                override fun onStop() = stopPlayback()
            })
            isActive = true
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ghostream:playback")
            .apply { setReferenceCounted(false); acquire(MAX_HOLD_MS) }
        main.postDelayed(tick, CHECK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> control(true)
            ACTION_PAUSE -> control(false)
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        session?.release()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun control(play: Boolean) {
        VideoCtl.command(BrowserChrome.activeWeb, if (play) "play" else "pause")
        changed(play)
    }

    private fun stopPlayback() {
        VideoCtl.command(BrowserChrome.activeWeb, "pause")
        stopSelf()
    }

    /** Follows the page: a video paused or started by the page itself (the next one in a playlist) or its controls. */
    private fun follow() {
        val web = BrowserChrome.activeWeb ?: return stopSelf()
        val now = web.title.orEmpty()
        if (now != title) { title = now; changed(playing) }
        web.evaluateJavascript(VideoCtl.SIZE) { size ->
            val on = !size.isNullOrEmpty() && size != "\"\""
            if (on != playing) changed(on)
            if (!on && System.currentTimeMillis() - pausedAt > IDLE_STOP_MS) stopSelf()
        }
    }

    private fun changed(play: Boolean) {
        if (!play && playing) pausedAt = System.currentTimeMillis()
        playing = play
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build()) }
            .onFailure { Log.w(TAG, "notification update failed", it) }
    }

    private fun build(): Notification {
        val web = BrowserChrome.activeWeb
        val private = BrowserChrome.activePrivate
        val name = if (private) "Private tab" else web?.title?.takeIf { it.isNotBlank() } ?: "Ghostream"
        val site = if (private) "" else AdBlock.hostOf(web?.url.orEmpty().lowercase())?.removePrefix("www.").orEmpty()
        session?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, site)
                .build(),
        )
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = if (playing) action(ACTION_PAUSE, R.drawable.ic_pip_pause, "Pause", 1) else action(ACTION_PLAY, R.drawable.ic_pip_play, "Play", 2)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pip_play)
            .setContentTitle(name)
            .setContentText(site)
            .setContentIntent(open)
            .setDeleteIntent(service(ACTION_STOP, 3))
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(if (private) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
            .addAction(toggle)
            .addAction(action(ACTION_STOP, android.R.drawable.ic_menu_close_clear_cancel, "Stop", 4))
            .addAction(downloadAction())
            .setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0))
            .build()
    }

    /**
     * The Download button opens the app itself (an activity PendingIntent, which Android always allows from a
     * notification), and MainActivity passes the request on through [downloadRequested].
     */
    private fun downloadAction(): Notification.Action {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_DOWNLOAD_PLAYING, true)
        val pending = PendingIntent.getActivity(this, DOWNLOAD_REQUEST, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.stat_sys_download), "Download", pending).build()
    }

    private fun service(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, PlaybackService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private fun action(action: String, icon: Int, label: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), label, service(action, code)).build()

    companion object {
        private const val TAG = "PlaybackService"
        private const val DOWNLOAD_REQUEST = 5
        /** On the intent the Download button opens MainActivity with. */
        const val EXTRA_DOWNLOAD_PLAYING = "download_playing"
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_PLAY = "play"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_STOP = "stop"
        private const val CHECK_MS = 2_000L
        private const val IDLE_STOP_MS = 5 * 60_000L // paused this long: the notification goes and the app may sleep
        private const val MAX_HOLD_MS = 4 * 3600_000L

        @Volatile var running = false
            private set

        /** Set by the notification's Download button; the Browser tab clears it once it has opened the download. */
        var downloadRequested by mutableStateOf(false)

        /** Something started playing in the Browser tab. Started now, while the app is on screen, as Android requires. */
        fun start(ctx: Context) {
            if (running) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java)) }
                .onFailure { Log.w(TAG, "could not start background playback", it) }
        }

        fun stop(ctx: Context) {
            if (running) ctx.stopService(Intent(ctx, PlaybackService::class.java))
        }
    }
}
