package com.julia.mediabuttonblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
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
            // v1.13: also keep the listener registration in sync with the user's
            // current Notification access state, so granting the permission while
            // the service is already running takes effect within one tick without
            // needing the master switch to be cycled.
            maybeUpdateSessionsChangedListener()
            // v1.12: every tick is a full soft-restart of our service-internal state.
            // The reporter on Android 15 confirmed that even a brand-new MediaSession
            // (v1.11) is NOT enough on its own — the only thing that reliably
            // restores media-button routing after a missed call or a voice message
            // is toggling the master switch, which destroys *and recreates* the
            // entire BlockerService. The hypothesis is that toggling also re-runs
            // startForeground() (refreshing the FGS as TYPE_MEDIA_PLAYBACK) and
            // creates a fresh silent MediaPlayer. v1.12 reproduces all three in
            // place, every second, without actually killing the Service.
            performSoftRestart()
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    // --- Diagnostic counters (v1.9, expanded in v1.11) ------------------------------
    //
    // These are shown live in the foreground-service notification text so the user
    // can read them off the screen during testing and the developer can tell:
    //   m = last seen audio mode (numeric AudioManager.MODE_*)
    //   modeChangeCount = mode-listener firings
    //   reassertCount   = cheap reassert calls (state bump + silent-player check)
    //   sessionSwapCount = number of MediaSession recreate-and-replace cycles
    //   buttonCount     = media-button events actually delivered to our callback
    //
    // The notification only refreshes on real semantic events (button block, mode
    // change, session swap) so TalkBack doesn't re-announce on every 1Hz tick.
    private var modeChangeCount: Int = 0
    private var reassertCount: Int = 0
    private var sessionSwapCount: Int = 0
    private var buttonCount: Int = 0
    private var sessionsChangedCount: Int = 0
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

    // --- Active-sessions listener (v1.13) -------------------------------------------
    //
    // [MediaSessionManager.OnActiveSessionsChangedListener] gives us a system signal
    // every time another app's MediaSession is created, destroyed or has its active
    // state changed. Concretely:
    //   - Incoming call: the dialer/telephony stack publishes its own session, then
    //     tears it down when the call ends.
    //   - Voice message in Telegram / WhatsApp / etc: the messenger publishes a
    //     short-lived session for the playback, then releases it.
    // In both cases, on Android 15 the in-place soft-restart we already do every
    // 1 s (v1.12) is not enough, but the user reports that the master switch off+on
    // (which actually kills and respawns the Service) does work. The listener gives
    // us a precise event-driven trigger to re-run our soft-restart at the moment a
    // foreign session goes away — the moment when our own session most needs to be
    // pushed back to the top of the routing list.
    //
    // This API requires the user to grant our app "Notification access" in system
    // settings (a one-time action). Without that grant,
    // [MediaSessionManager.addOnActiveSessionsChangedListener] throws
    // SecurityException. We treat the permission as optional: when it's granted we
    // wire up the listener; when it isn't, the service still runs (just without
    // event-driven recovery, the 1 Hz fallback still applies).
    //
    // Two safety rules from v1.7/v1.8 are reused as-is:
    //   1. Deferred registration (via [refreshHandler.post]) so the new MediaSession
    //      created in [initMediaSession] has fully propagated through the system.
    //   2. Ignore the very first callback after registration — it carries the
    //      current state at registration time and would otherwise trigger a
    //      reassert before initialisation has settled (v1.3-style regression).
    //
    // To avoid a feedback loop with our own [performSoftRestart] (which destroys
    // and recreates a MediaSession every 1 s, each of which fires this listener),
    // we filter the controllers list to packages OTHER than our own. We only act
    // when the set of foreign sessions changes — our own session swaps are then
    // invisible to the trigger.
    private var sessionsManager: MediaSessionManager? = null
    private var sessionsChangedListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var sawFirstSessionsChangedCallback: Boolean = false
    private var lastForeignSessionPackages: Set<String> = emptySet()

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
     * On top of those, in v1.13 we also register a
     * [MediaSessionManager.OnActiveSessionsChangedListener] when the user has granted
     * Notification access. That one gives a more direct "a foreign media session
     * appeared/disappeared" signal which covers calls and voice messages on Android
     * 15 even when the audio-mode listener stays silent.
     *
     * Registration is deferred by one main-thread tick so the MediaSession we just
     * created in [initMediaSession] has a chance to finish propagating through the
     * system; the synchronous registration-time callback that fires immediately after
     * is then dropped by the [sawFirstAudioModeCallback] /
     * [sawFirstCallStateCallback] / [sawFirstSessionsChangedCallback] guards.
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
            maybeUpdateSessionsChangedListener()
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

        unregisterSessionsChangedListener()
    }

    /**
     * Brings the [MediaSessionManager.OnActiveSessionsChangedListener] registration
     * in line with the current Notification access permission.
     *
     * Called both at service start (deferred) and from the periodic refresh tick so
     * that granting the permission after the service is already up takes effect
     * within ~1 s without needing the master switch to be cycled. Conversely, if
     * the user revokes the permission while the service is running, we drop the
     * listener cleanly to avoid leaks / SecurityExceptions on the next callback.
     */
    private fun maybeUpdateSessionsChangedListener() {
        val granted = NotificationAccessHelper.isGranted(this)
        val registered = sessionsChangedListener != null
        if (granted && !registered) {
            runCatching { registerSessionsChangedListener() }
                .onFailure { Log.w(TAG, "Failed to register sessions-changed listener", it) }
        } else if (!granted && registered) {
            unregisterSessionsChangedListener()
        }
    }

    private fun registerSessionsChangedListener() {
        val sm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val component = ComponentName(this, MediaSessionsNotificationListener::class.java)
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            handleActiveSessionsChanged(controllers)
        }
        sm.addOnActiveSessionsChangedListener(listener, component, refreshHandler)
        sessionsManager = sm
        sessionsChangedListener = listener
        sawFirstSessionsChangedCallback = false
        lastForeignSessionPackages = emptySet()
        Log.d(TAG, "Registered OnActiveSessionsChangedListener")
    }

    private fun unregisterSessionsChangedListener() {
        val sm = sessionsManager
        val listener = sessionsChangedListener
        if (sm != null && listener != null) {
            runCatching { sm.removeOnActiveSessionsChangedListener(listener) }
        }
        sessionsChangedListener = null
        sessionsManager = null
        sawFirstSessionsChangedCallback = false
        lastForeignSessionPackages = emptySet()
    }

    /**
     * Reacts to a change in the set of active media sessions on the device.
     *
     * Our 1 Hz [performSoftRestart] swaps our own [MediaSessionCompat] every tick,
     * which itself fires this callback. To avoid a feedback loop we filter
     * controllers to packages OTHER than our own and only act when the set of
     * foreign packages changes — i.e. when an external app (dialer, Telegram,
     * WhatsApp, etc.) appears or disappears as a media participant.
     *
     * On any such change we kick a [performSoftRestart] in addition to our 1 Hz
     * baseline. The hypothesis is that Android 15 hands media-button routing back
     * promptly when a foreign session is published / withdrawn, so a same-tick
     * soft-restart at that exact moment maximises the chance that we reclaim it.
     */
    private fun handleActiveSessionsChanged(controllers: List<MediaController>?) {
        sessionsChangedCount++
        val foreign = controllers
            ?.mapNotNullTo(mutableSetOf()) { ctrl ->
                val pkg = runCatching { ctrl.packageName }.getOrNull()
                if (pkg.isNullOrEmpty() || pkg == packageName) null else pkg
            }
            ?: emptySet()
        if (!sawFirstSessionsChangedCallback) {
            sawFirstSessionsChangedCallback = true
            lastForeignSessionPackages = foreign
            Log.d(TAG, "Sessions-changed initial state foreign=$foreign, ignoring (registration flash)")
            return
        }
        val previousForeign = lastForeignSessionPackages
        lastForeignSessionPackages = foreign
        if (foreign != previousForeign) {
            Log.d(
                TAG,
                "Foreign media sessions changed prev=$previousForeign current=$foreign, soft-restarting",
            )
            performSoftRestart()
            // Notification only updates here, not on every 1 Hz tick — keeps TalkBack
            // quiet but still surfaces "слушатель=N" so the user / dev can verify the
            // listener is firing.
            updateNotificationContent()
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
     * v1.11: added "свап" (session-swap) counter so the user / dev can confirm the
     * automatic session recreation cycle is firing. Updated only on real semantic
     * events (button block, mode change, session swap) so TalkBack doesn't repeat
     * itself on every 1Hz tick.
     */
    private fun buildDiagnosticLine(): String {
        return getString(
            R.string.notification_diag,
            lastSeenModeForUi,
            modeChangeCount,
            reassertCount,
            sessionSwapCount,
            sessionsChangedCount,
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
        mediaSession = createNewMediaSession()
    }

    /**
     * Builds a brand-new active [MediaSessionCompat] wired up with our blocking
     * callback, the supported-actions mask, and a STATE_PLAYING playback state.
     * Used both for the initial session in [onCreate] and for [swapMediaSession].
     */
    @Suppress("DEPRECATION")
    private fun createNewMediaSession(): MediaSessionCompat = MediaSessionCompat(this, TAG).apply {
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
            .setState(
                PlaybackStateCompat.STATE_PLAYING,
                System.currentTimeMillis(),
                1.0f,
            )
            .setActions(supportedActions)
            .build()
        setPlaybackState(state)
        isActive = true
    }

    /**
     * Performs a full soft-restart of our service-internal state without actually
     * stopping and restarting the Service. This is the in-place equivalent of the
     * user toggling the master switch off and back on — reported on Android 15 to
     * be the only operation that reliably restores media-button routing after a
     * missed call or a voice message.
     *
     * Unlike v1.11's MediaSession-only swap, this also:
     *   - Tears down and recreates the silent [MediaPlayer]. After a call the audio
     *     focus stack can leave the player in a state where `isPlaying` returns
     *     `true` while audio is no longer flowing, so we don't trust the flag and
     *     just rebuild it.
     *   - Re-calls [startForeground] with `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`.
     *     The hypothesis is that during a call Android 15 temporarily demotes our
     *     FGS type, and only re-asserting it via `startForeground` puts us back
     *     into the priority class that wins media-button routing.
     *
     * Order of operations is chosen to minimise the window during which we lack a
     * routable session:
     *   1. Build a new active MediaSession.
     *   2. Swap our field reference to it (now there are two of our sessions, the
     *      new one is most-recently-active so MediaSessionManager prefers it).
     *   3. Tear down the old MediaSession.
     *   4. Tear down + rebuild the silent MediaPlayer.
     *   5. Re-call startForeground() to re-assert FGS type.
     *
     * Notification updates are intentionally NOT triggered here. With a 1 Hz cadence
     * any notification rebuild would cause TalkBack to re-announce every second and
     * make the app unusable for screen-reader users (we already learned this in
     * v1.10). The session-swap counter still increments and will surface in the
     * next event-driven notification update.
     */
    private fun performSoftRestart() {
        val newSession = createNewMediaSession()
        val oldSession = if (::mediaSession.isInitialized) mediaSession else null
        mediaSession = newSession

        runCatching {
            oldSession?.isActive = false
            oldSession?.release()
        }

        runCatching {
            silencePlayer?.stop()
            silencePlayer?.release()
        }
        silencePlayer = null
        runCatching { startSilentLoop() }
            .onFailure { Log.w(TAG, "Failed to restart silent loop", it) }

        // Re-assert our FGS type. With the same NOTIFICATION_ID this updates the
        // existing notification in place rather than posting a new one, so TalkBack
        // doesn't re-announce.
        runCatching {
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
        }.onFailure { Log.w(TAG, "Failed to re-assert foreground status", it) }

        sessionSwapCount++
        Log.d(TAG, "Soft restart complete (#$sessionSwapCount)")
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
        // v1.12: every tick performs a full soft-restart, so the swap interval
        // *is* the recovery latency after a call / voice message ends. 1s keeps it
        // perceptually instant.
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
