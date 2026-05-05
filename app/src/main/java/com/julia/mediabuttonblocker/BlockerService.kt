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
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import java.util.concurrent.Executor

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

    // --- Diagnostic counters (v1.9) -------------------------------------------------
    //
    // These are shown live in the foreground-service notification text so the user
    // can read them off the screen during testing and the developer can tell:
    //   M = mode changes seen by AudioManager.OnModeChangedListener
    //   R = strong-reassert calls (forced isActive false→true + state bump)
    //   B = media-button events actually delivered to our session callback
    //   m = last seen audio mode (numeric AudioManager.MODE_*)
    //
    // Without these, regressions were impossible to debug remotely — we couldn't tell
    // whether the listener even fired or if the reassert was just too weak.
    private var modeChangeCount: Int = 0
    private var reassertCount: Int = 0
    private var buttonCount: Int = 0
    private var lastSeenModeForUi: Int = AudioManager.MODE_NORMAL
    private var lastReassertWallClock: Long = 0L

    // --- Audio-mode / call-state listening -------------------------------------------
    //
    // We watch for the system audio-mode going back to MODE_NORMAL from anything
    // "non-normal" (RINGTONE / IN_CALL / IN_COMMUNICATION / CALL_SCREENING) so we can
    // re-assert our MediaSession the moment a call or messenger voice playback ends.
    // This addresses the v1.2-era regression "blocking dies after I take a call /
    // listen to a voice message": while another app holds in-call or in-communication
    // mode, that audio stack temporarily steals media-button routing, and Android
    // does not always hand it back to us when the mode returns to NORMAL.
    //
    // We prefer AudioManager.OnModeChangedListener (API 31+) because:
    //   - It does not require READ_PHONE_STATE (telephony listeners on Android 12+
    //     are filtered to IDLE for callers without that permission, which is exactly
    //     why v1.7 didn't actually fire on the user's missed call).
    //   - It also covers messenger voice messages, which set MODE_IN_COMMUNICATION
    //     but never trigger TelephonyManager call states.
    //
    // On API < 31 we fall back to PhoneStateListener (best-effort, may silently no-op
    // if READ_PHONE_STATE isn't held). The user's primary device runs Android 15.
    //
    // Two safety rules apply, learned the hard way from v1.3 (which broke blocking
    // entirely):
    //   1. We register the listener AFTER initMediaSession() / startSilentLoop()
    //      so the session is already known to MediaSessionManager.
    //   2. We IGNORE the first callback we receive after registration. Both APIs
    //      fire synchronously with the current state at registration time; reacting
    //      to that 'flash' would run a reassert before our session has fully
    //      propagated through the system, which is exactly what knocked us off the
    //      routing list in v1.3.
    private var audioManager: AudioManager? = null
    private var modeChangedListener: AudioManager.OnModeChangedListener? = null
    private var lastSeenAudioMode: Int = AudioManager.MODE_NORMAL
    private var sawFirstAudioModeCallback: Boolean = false

    private var telephonyManager: TelephonyManager? = null
    private var legacyPhoneStateListener: PhoneStateListener? = null
    private var lastSeenCallState: Int = TelephonyManager.CALL_STATE_IDLE
    private var sawFirstCallStateCallback: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        startInForeground()
        initMediaSession()
        startSilentLoop()
        // Mode / telephony listener has to come after the session is fully wired up.
        // See registerCallStateListeners() for why we also wait one tick.
        registerCallStateListeners()
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
        unregisterCallStateListeners()
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

    /**
     * Re-affirms our MediaSession as the active media-button recipient.
     *
     * v1.9 strengthens this from a "gentle" timestamp bump (which seems to be
     * insufficient on Android 15 once another audio stack has held priority during a
     * call or voice message) into a stronger sequence:
     *
     *   1. If the silent player is dead / paused / errored (which can happen when the
     *      audio focus is yanked during a call), tear it down and start a fresh one.
     *      Without an audible playback our session loses its "live media app" status.
     *   2. Toggle isActive false → true. This is what v1.3 did unsafely from a
     *      synchronous registration callback inside onCreate. Once we are well past
     *      onCreate (we only call this from the periodic refresh handler or a
     *      deferred listener that has already dropped its first "flash" callback),
     *      this is the documented way to push our session back to the top of
     *      MediaSessionManager's most-recently-active list.
     *   3. Push a fresh PlaybackStateCompat with a current timestamp.
     *
     * v1.10: this no longer rebuilds the notification on every call. The 1Hz periodic
     * refresh would otherwise fire `notify()` once per second, which TalkBack picks
     * up as a fresh announcement and reads aloud. The notification is now only
     * rebuilt when something semantically interesting happens (a media button is
     * blocked, the audio mode changes), driven from those callsites directly.
     */
    private fun refreshSessionState() {
        if (!::mediaSession.isInitialized) return
        reassertCount++
        lastReassertWallClock = System.currentTimeMillis()
        runCatching {
            ensureSilencePlayerRunning()

            // Push to top of active-sessions list. Safe outside onCreate now that all
            // listeners are deferred and ignore their first registration-time callback.
            if (mediaSession.isActive) mediaSession.isActive = false
            mediaSession.isActive = true

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
        }
    }

    /**
     * Ensures the silent loop is actually running. If the existing player is null,
     * not playing, or in an error state, releases it and creates a fresh one.
     * v1.9: needed because during a call the audio system can pause / error the
     * MediaPlayer, after which `start()` no-ops without any error indication.
     */
    private fun ensureSilencePlayerRunning() {
        val player = silencePlayer
        val running = player?.let { runCatching { it.isPlaying }.getOrDefault(false) } == true
        if (running) return
        runCatching {
            player?.stop()
            player?.release()
        }
        silencePlayer = null
        startSilentLoop()
    }

    // --- mode / telephony listener wiring --------------------------------------------

    /**
     * Subscribes to whichever call-state-ish signal is available without requiring an
     * extra runtime permission, so we can reassert our session as soon as a call or
     * messenger voice playback ends.
     *
     * Preference order:
     *   - API 31+: [AudioManager.OnModeChangedListener]. Fires for ringtone, in-call,
     *     in-communication, call-screening and back to normal mode. Doesn't need
     *     READ_PHONE_STATE. Also covers messenger voice messages that switch the
     *     audio mode.
     *   - API < 31: legacy [PhoneStateListener]. Best-effort — will silently no-op if
     *     the system requires READ_PHONE_STATE and we haven't been granted it.
     *
     * Registration is deferred by one main-thread tick so the MediaSession we just
     * created in [initMediaSession] has a chance to finish propagating through the
     * system; the synchronous registration-time callback that fires immediately after
     * is then dropped by the [sawFirstAudioModeCallback] / [sawFirstCallStateCallback]
     * guards.
     */
    private fun registerCallStateListeners() {
        refreshHandler.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { registerAudioModeListener() }
                    .onFailure { Log.w(TAG, "Failed to register audio-mode listener", it) }
            } else {
                runCatching { registerLegacyPhoneStateListener() }
                    .onFailure { Log.w(TAG, "Failed to register legacy phone-state listener", it) }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerAudioModeListener() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am
        lastSeenAudioMode = am.mode
        lastSeenModeForUi = am.mode
        val executor: Executor = mainExecutor
        val listener = AudioManager.OnModeChangedListener { mode ->
            handleAudioModeChanged(mode)
        }
        am.addOnModeChangedListener(executor, listener)
        modeChangedListener = listener
    }

    @Suppress("DEPRECATION")
    private fun registerLegacyPhoneStateListener() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        telephonyManager = tm
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallStateChanged(state)
            }
        }
        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        legacyPhoneStateListener = listener
    }

    /**
     * Handles an audio-mode change. We reassert when the mode returns to
     * [AudioManager.MODE_NORMAL] from any non-normal state (ringtone, in-call,
     * in-communication, call-screening). The first callback after registration
     * carries the current mode and is ignored — see [sawFirstAudioModeCallback].
     */
    private fun handleAudioModeChanged(mode: Int) {
        val previous = lastSeenAudioMode
        lastSeenAudioMode = mode
        lastSeenModeForUi = mode
        if (!sawFirstAudioModeCallback) {
            sawFirstAudioModeCallback = true
            Log.d(TAG, "AudioManager initial mode=$mode, ignoring (registration flash)")
            // Don't push a notification update here — it would just rebroadcast the
            // same content TalkBack already announced when the service started.
            return
        }
        modeChangeCount++
        Log.d(TAG, "Audio mode change: prev=$previous current=$mode")
        if (mode == AudioManager.MODE_NORMAL && previous != AudioManager.MODE_NORMAL) {
            Log.d(TAG, "Audio mode returned to NORMAL, reasserting session")
            refreshSessionState()
        }
        // Mode changes are real, infrequent events — it's fine for TalkBack to read
        // the updated counter once per change.
        updateNotificationContent()
    }

    /**
     * Legacy fallback: handles the actual call-state transition. We only reassert our
     * session when the call returns to IDLE from a non-IDLE state. The first callback
     * after registration is ignored.
     */
    private fun handleCallStateChanged(state: Int) {
        val previous = lastSeenCallState
        lastSeenCallState = state
        if (!sawFirstCallStateCallback) {
            sawFirstCallStateCallback = true
            Log.d(TAG, "Telephony initial state=$state, ignoring (registration flash)")
            return
        }
        if (state == TelephonyManager.CALL_STATE_IDLE &&
            previous != TelephonyManager.CALL_STATE_IDLE
        ) {
            Log.d(TAG, "Call ended (prev=$previous), reasserting session")
            refreshSessionState()
        }
    }

    private fun unregisterCallStateListeners() {
        val am = audioManager
        val listener = modeChangedListener
        if (am != null && listener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.removeOnModeChangedListener(listener) }
        }
        modeChangedListener = null
        audioManager = null

        val tm = telephonyManager
        @Suppress("DEPRECATION")
        runCatching {
            legacyPhoneStateListener?.let { tm?.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
        legacyPhoneStateListener = null
        telephonyManager = null
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
        val notification = buildNotification()
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

    private fun buildNotification(): Notification {
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(buildDiagnosticLine())
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
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * Diagnostic line shown in the FGS notification text.
     *
     * v1.10 changes:
     *   - Russian labels so a screen reader's announcement is meaningful.
     *   - No "a=Ns" field. The per-second-changing seconds-ago value caused
     *     TalkBack to re-announce the notification every periodic refresh, which
     *     made the app unusable for the original blind user during testing.
     *   - Updated only when a real semantic event happens (button blocked or
     *     audio mode changed), not on every periodic 1Hz reassert.
     */
    private fun buildDiagnosticLine(): String {
        return getString(
            R.string.notification_diag,
            lastSeenModeForUi,
            modeChangeCount,
            reassertCount,
            buttonCount,
        )
    }

    private fun updateNotificationContent() {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(BlockingCallback())

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

    private inner class BlockingCallback : MediaSessionCompat.Callback() {
        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            // Swallow the event. Returning true tells the framework not to fall back
            // to the default onPlay/onPause handlers and not to forward it elsewhere.
            buttonCount++
            Log.d(TAG, "Blocked media button event: $mediaButtonEvent (total=$buttonCount)")
            updateNotificationContent()
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
        private const val REFRESH_INTERVAL_MS = 1_000L
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
