# Beta testing and known limitations

## Physical device testing

The maintainer reports Radio+ confirmed working on their physical **Junsun V7**.
Other testing has been emulator-only; no other head-unit model is verified.
This is a device-specific report, not a guarantee for every V7 firmware variant
or proof that every feature and long-running scenario has passed. The checks
below distinguish automated evidence from hardware-dependent regression testing.

## Verified for this source release

On 4 October 2026 the clean public project built with Gradle 8.11.1, Android SDK
33 and the local JDK 21 runtime (Java 17 source/target compatibility):

- 95 unit tests passed, with no failures or errors.
- Debug APK/AAB logo-isolation verification passed.
- Unsigned release build passed; its DEX, binary manifest and resource table were
  compared with the signed distribution candidate and matched byte-for-byte.
- Lint: 0 errors, 12 warnings. Old target API/dependency-version checks are disabled
  explicitly; a clean lint result is not Play compliance or a security audit.
- Android 13 emulator: three instrumentation tests passed. They cover English
  default on a Finnish configuration, imported-image size/pixel preservation,
  resource isolation and removal/recycled-card behavior.
- The signed APK contains no station-logo assets or embedded native/vendor libraries.

The demo screenshot was captured from the private preview with six station logos
at 1280×720. The public variant uses the same shared UI sources but ships without
the private logo catalogue. The head-unit image is an AI-assisted composite, not
a photograph of this release running in a physical vehicle.

## Not proven by automated tests

| Area | Remaining device verification |
| --- | --- |
| FM audio | Sustained audible playback, dropouts, source routing and volume stability |
| Pause and resume | Real speaker mute/unmute, not just a changed selection outline |
| Steering controls | Physical next/previous/mute events and duplicate-event handling on each ROM |
| AM and scanning | Available bands, scan cancellation, manual tuning and weak-station sensitivity |
| RDS | Complete station names/radio text on the actual receiver and broadcast |
| Sleep and boot | Cold boot, ACC sleep/wake and firmware background restrictions |
| Widgets | Host launcher support; a locked manufacturer widget list may reject third-party widgets |

An emulator has no stock FMPlugService or analog tuner. A responsive UI and
simulated metadata do not demonstrate audible playback. Missing vendor responses
must not be treated as evidence that a physical device will work.

Previously reported audio dropouts, fluctuating volume and physical steering-key
problems require regression testing in a real head unit before they can be
declared resolved across devices. Please do not infer universal compatibility
from the model name or minimum Android version.

## Suggested parked-device test

1. Confirm the stock radio works first and note firmware/version without publishing
   device identifiers. Use a low safe volume.
2. Test cold start, selecting a station, next/previous in Stations and Favorites,
   tap-to-pause/resume, and at least a 30-minute stationary playback session.
3. Switch between another audio app and Radio+, then verify which source is audible.
4. Test manual FM and AM tuning, scan cancellation, saving/renaming and app restart.
5. Import a PNG/JPEG from USB, disconnect the USB drive and restart. Test removal.
6. Test physical steering buttons and ACC wake separately. Record UI state and
   actual audio separately. Do not configure or debug while driving.

## Run tests yourself

```sh
gradle :app:testPlayDebugUnitTest :app:lintPlayDebug :app:verifyPlayLogoIsolation
gradle :app:assemblePlayDebugAndroidTest
adb install -r app/build/outputs/apk/play/debug/app-play-debug.apk
adb install -r app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk
adb shell am instrument -w -r fi.radioplus.app.play.debug.test/androidx.test.runner.AndroidJUnitRunner
```

If several devices are connected, select one with `adb -s <serial>`. These
instrumentation tests use a separate debug package, not the release app.

