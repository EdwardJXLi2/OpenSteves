# OpenSteves

An open source Android player for [Rick Steves Europe Audio Tours](https://www.ricksteves.com/watch-read-listen/audio/audio-tours).

## Features

- Download tours, maps, and transcripts for offline use.
- Play audio in the background with notification and lock-screen controls.
- Listen together and share audio over Bluetooth, with playback controls on every phone.
- Import your own audio and save downloaded audio to your device.

Pair phones in Android Bluetooth settings before starting a shared session. Shared sessions require no Wi-Fi or mobile data.

## Requirements

- Android 8.0 or newer device.
- Android Studio or the Android Gradle plugin toolchain.
- JDK 21 and Android SDK 36.1 for building.

## Build

```sh
./gradlew :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

See the [build workflow](.github/workflows/build-apks.yml) for CI signing and publishing configuration.

## Content

Audio, maps, images, and transcripts belong to their respective copyright holders.
OpenSteves is not affiliated with Rick Steves Europe. Tour content is not bundled with the application.
