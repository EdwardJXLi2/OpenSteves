# OpenSteves

Open Source Android Player for [Rick Steves Europe Audio Tours](https://www.ricksteves.com/watch-read-listen/audio/audio-tours)

Download tours for offline listening, read transcripts, and view maps.
Playback continues in the background with notification and lock-screen controls.

Requires Android 8.0 (API 26) or later.

## Offline tours

Internet access is required to load the catalog and download tours from the Rick Steves Audio Europe servers.
Downloaded tours, maps, and transcripts remain available offline.

Stop the current tour before deleting or updating its download.

Walking tour transcripts are not timed captions.

## Listen together

Listen to the same tour on multiple phones through a shared Bluetooth session.
Each phone plays its own downloaded audio, so the session requires no Wi-Fi or mobile data.

1. Download the same tour revision on every phone before leaving internet coverage.
2. Pair each guest phone with the host through Android Bluetooth settings.
3. Start the downloaded tour on the host, then select **Start a session** in **Together**.
4. On each guest, open **Together**, select **Show paired phones**, then select the host.

Everyone can control playback.
Keep Bluetooth enabled and the phones within range.
Leaving or losing the host pauses guest playback. Rejoin manually after restoring the connection.
The host cannot transfer its role during a session.

Phones and headphones can add different audio delays.
Audible synchronization and Bluetooth reliability require testing with physical phones.

## Content

Audio, maps, images, and transcripts belong to their respective copyright holders.
OpenSteves is not affiliated with Rick Steves Europe. Tour content is not bundled with the application.

## Development

Open this directory in Android Studio. Use JDK 17 or later and install Android SDK Platform 36.1.

Set the SDK location in Android Studio or define `ANDROID_HOME` before building.

```sh
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
