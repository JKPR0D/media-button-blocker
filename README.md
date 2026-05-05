# Media Button Blocker

🌐 [Русский](README.ru.md) · **English**

A small Android app that intercepts media-button presses (play / pause /
headset hook) on wired and Bluetooth headphones, so that other apps (for
example **TeamTalk**, which uses that button to mute/unmute the mic) don't
react to them.

## How it works

The app starts a foreground service that registers a `MediaSessionCompat`
with `FLAG_HANDLES_MEDIA_BUTTONS` and `FLAG_HANDLES_TRANSPORT_CONTROLS`,
keeps it active, and plays a short **silent** audio file in a loop
(volume 0) so Android treats our session as "live" and routes media-button
events to it.

The `onMediaButtonEvent` callback returns `true` — the event is swallowed
and never reaches TeamTalk / Discord / Telegram / etc.

Every 3 seconds the playback state is re-asserted as `STATE_PLAYING` so
our app stays the "freshest" active media session and doesn't lose
priority to other messengers that re-create their session mid-call.

## Installation

1. On your phone, allow installation from unknown sources
   (Settings → Security → Install unknown apps).
2. Copy `app-debug.apk` to the phone and open it — the system will offer
   to install.
3. Once installed, launch the **"Media Button Blocker"** app.

## Usage

1. Open the app.
2. Flip the **"Block media button"** switch on.
3. On Android 13+ grant the notification permission (required by the
   system for foreground services).
4. A persistent **"Media button blocked"** notification will appear in
   the shade — this is required by Android, you can't hide it.
5. Open TeamTalk and use it normally. Pressing the headset button will
   no longer toggle the microphone.
6. To turn blocking off — open the app and flip the switch back, or
   tap **"Stop"** in the notification.

## Compatibility

- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 34 (Android 14)
- **Tested** against `BearWare/TeamTalk5` (its Android client uses
  `MediaSessionCompat` with the same flags, so media-button routing
  goes through the standard Android mechanism that we intercept).

## Known limitations

- If another app registers its media session **after** ours, it will
  temporarily steal routing until the next refresh cycle (≤ 3 s).
- The persistent notification cannot be hidden — Android requires it for
  any foreground service.
- The silent audio loop consumes a tiny bit of battery (minimal).

## Building from source

Requires JDK 17 and the Android SDK with `platforms;android-34` and
`build-tools;34.0.0`.

```bash
# Point Gradle at your Android SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

# Build the debug APK
./gradlew :app:assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

## License

MIT — do whatever you want with the code.
