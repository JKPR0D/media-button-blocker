package com.julia.mediabuttonblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver

/**
 * Foreground service that intercepts headphone media-button events.
 *
 * How it works:
 *   1. We register a [MediaSessionCompat] with `FLAG_HANDLES_MEDIA_BUTTONS` and mark it
 *      active. Android's `MediaSessionManager` routes media-button events to the most
 *      recently active session, so by setting our session's playback state to PLAYING
 *      and keeping a silent audio loop running we become the preferred recipient.
 *   2. Our [MediaSessionCompat.Callback.onMediaButtonEvent] returns `true` to consume
 *      the event without forwarding it. Discord / Telegram / etc. therefore never see
 *      the play/pause keypress, so they don't toggle the microphone.
 */
class BlockerService : Service() {

    private lateinit var mediaSession: MediaSessionCompat
    private var silencePlayer: MediaPlayer? = null
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable: Runnable = object : Runnable {
        override fun run() {
            // Periodically re-affirm our session as the most-recently-active one so
            // other apps (e.g. TeamTalk re-creating its own session mid-call) cannot
            // steal media-button routing back from us.
            refreshSessionState()
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        startInForeground()
        initMediaSession()
        startSilentLoop()
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BlockerPrefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        // Forward any media-button intents the system delivers to the receiver into our
        // session so the callback fires.
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        refreshHandler.removeCallbacks(refreshRunnable)
        runCatching {
            silencePlayer?.stop()
            silencePlayer?.release()
        }
        silencePlayer = null
        runCatching {
            mediaSession.isActive = false
            mediaSession.release()
        }
        super.onDestroy()
    }

    private fun refreshSessionState() {
        if (!::mediaSession.isInitialized) return
        runCatching {
            if (!mediaSession.isActive) mediaSession.isActive = true
            val supportedActions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            mediaSession.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(
                        PlaybackStateCompat.STATE_PLAYING,
                        System.currentTimeMillis(),
                        1.0f,
                    )
                    .setActions(supportedActions)
                    .build(),
            )
            silencePlayer?.takeIf { !it.isPlaying }?.start()
        }
    }

    // --- setup helpers -----------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startInForeground() {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BlockerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_block_notification)
            .setContentIntent(openAppIntent)
            .addAction(
                R.drawable.ic_block_notification,
                getString(R.string.notification_stop_action),
                stopIntent,
            )
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(BlockingCallback)

            val supportedActions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

            val state = PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1.0f)
                .setActions(supportedActions)
                .build()
            setPlaybackState(state)
            isActive = true
        }
    }

    private fun startSilentLoop() {
        // Playing a tiny silent loop helps the system treat us as a "live" media app so
        // headphone media-button events get routed to our session before any other.
        // Volume is forced to 0 so we don't cover real audio.
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        silencePlayer = MediaPlayer().apply {
            setAudioAttributes(attrs)
            isLooping = true
            setVolume(0f, 0f)
            val afd = resources.openRawResourceFd(R.raw.silence) ?: return@apply
            afd.use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error: what=$what extra=$extra")
                true
            }
            prepare()
            start()
        }
    }

    private object BlockingCallback : MediaSessionCompat.Callback() {
        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            // Swallow the event. Returning true tells the framework not to fall back
            // to the default onPlay/onPause handlers and not to forward it elsewhere.
            Log.d(TAG, "Blocked media button event: $mediaButtonEvent")
            return true
        }

        override fun onPlay() {}
        override fun onPause() {}
        override fun onStop() {}
        override fun onSkipToNext() {}
        override fun onSkipToPrevious() {}
    }

    companion object {
        private const val TAG = "BlockerService"
        private const val CHANNEL_ID = "blocker_channel"
        private const val NOTIFICATION_ID = 1001
        private const val REFRESH_INTERVAL_MS = 3_000L
        const val ACTION_STOP = "com.julia.mediabuttonblocker.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, BlockerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BlockerService::class.java))
        }

        @Suppress("UNUSED_PARAMETER")
        fun isAudioRouteHeadset(context: Context): Boolean {
            // Reserved for future use: detect if BT/wired headset is plugged in.
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            @Suppress("DEPRECATION")
            return am.isWiredHeadsetOn || am.isBluetoothA2dpOn || am.isBluetoothScoOn
        }
    }
}
