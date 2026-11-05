# OpenSteves

An independent Android player for Rick Steves Audio Europe, built with Kotlin, Jetpack Compose, and Material 3.

Download tours for offline listening, read transcripts, and view maps with pan and zoom controls.
Media3 provides background playback, media notifications, and lock-screen controls.

Requires Android 8.0 (API 26) or later.

## Tours and subtitles

The catalog and tour bundles come from the public Rick Steves Audio Europe servers.
Internet access is required to load the catalog and download tours.
Downloaded tours remain available offline.
Downloaded tours appear at the top of **Explore**.
Open a downloaded tour, then select **Delete download** in the toolbar to remove it.
Stop the current tour before deleting or updating its download.

The app follows the system theme until you select the sun or moon icon in **Explore**.
The app saves the selected appearance on the device.

Walking tours include maps and untimed transcripts.
Bundled VTT files generally contain chapter and image cues, not spoken captions.
Previously saved subtitles remain available offline.

## Listen together

Each phone plays its own downloaded audio while Bluetooth carries playback commands and timing updates.
The session requires no Wi-Fi or mobile data. Each session has one host.

1. Download the same tour revision on every phone before leaving internet coverage.
2. Pair each guest phone with the host through Android Bluetooth settings.
3. Start the downloaded tour on the host, then select **Start a session** in **Together**.
4. On each guest, open **Together**, select **Show paired phones**, then select the host.

Everyone can play, pause, seek, stop, and change speed between 0.5× and 2×.
The host orders commands and schedules playback changes 600 ms ahead.
Guests check audio hashes, estimate clock offsets, and correct playback drift from periodic host updates.
Keep Bluetooth enabled and the phones within range.
Leaving or losing the host pauses guest playback. Rejoin manually after restoring the connection.
The host cannot transfer its role during a session.

Synchronization uses playback positions, not measured sound output.
Phones and headphones can add different output delays. Audible synchronization and Bluetooth reliability require testing with physical phones.
Use headphones to avoid interference between nearby speakers.

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
