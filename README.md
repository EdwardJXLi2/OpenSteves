# OpenSteves

A basic Android app built with Kotlin, Jetpack Compose, and Material 3.

Requires Android 8.0 (API 26) or later.

## Development

Open this directory in Android Studio. Use JDK 17 or later and install Android SDK Platform 36.1.

Set the SDK location in Android Studio or define `ANDROID_HOME` before building.

```sh
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
