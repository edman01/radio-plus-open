# Build Radio+ from source

Required: **JDK 17**, **Gradle 8.11.1**, Android SDK **Platform 33** and Build Tools
**33.0.2**. This source release has no Gradle wrapper. Install Gradle or configure
that version in Android Studio. Set `ANDROID_HOME` to your SDK directory.
Dependency downloads need internet on the first build; the app has no internet permission.

## Build and test

```sh
gradle :app:testPlayDebugUnitTest :app:lintPlayDebug :app:verifyPlayLogoIsolation
gradle :app:assemblePlayDebugAndroidTest
```

Debug APK: `app/build/outputs/apk/play/debug/app-play-debug.apk`.
Its package is `fi.radioplus.app.play.debug`, separate from the release app.
The historical `play` flavor name does not imply Google Play availability.

## Emulator preview

For UI preview without tuner hardware, install the debug build:

```sh
adb install -r app/build/outputs/apk/play/debug/app-play-debug.apk
adb shell am start -n fi.radioplus.app.play.debug/fi.radioplus.app.MainActivity --ez preview true
```

Preview station names and radio text are fixtures, not live reception. Release
builds do not enable preview mode. The public build has no bundled station logos.
See [testing](TESTING.md) for instrumentation commands and hardware limitations.

## Reglink device-test build

The stock-assisted Reglink preview is explicitly opt-in:

```sh
gradle :app:testPlayDebugUnitTest :app:assemblePlayDebug :app:assemblePlayDebugAndroidTest -PreglinkStockAssisted=true
```

For a signed release test APK, use the same property with `:app:assemblePlayRelease`
and the signing variables below. Use `-PexperimentalTestBuild6=true` instead
to build Experimental Test Build 6 with the same profile checks. Without either
opt-in, Reglink control stays disabled. This does not change the Junsun backend
or enable arbitrary Reglink firmware.
Read the [requirements and limitations](EXPERIMENTAL.md#reglink-device-test-preview--s574-and-s602)
before testing. Never run manufacturer firmware or APKs in the emulator to claim
hardware validation; instrumentation uses synthetic protocol endpoints only.

## Release signing

Supply `RADIO_PLUS_KEYSTORE`, `RADIO_PLUS_STORE_PASSWORD`, `RADIO_PLUS_KEY_ALIAS`
and `RADIO_PLUS_KEY_PASSWORD` as environment variables, then run:

```sh
gradle :app:assemblePlayRelease
```

Without signing variables the release is unsigned. Never commit private keys or
passwords. Your own signing key cannot update the project's signed APK in place.
The source license does not grant rights to third-party artwork or manufacturer
software; see [the notices](../THIRD_PARTY_NOTICES.md).

[Back to Radio+](../README.md)
