# Beta testing and known limitations

## Physical device testing

The maintainer reports Radio+ confirmed working on their physical **Junsun V7 running Android 13**.
Other testing has been emulator-only; no other head-unit model is verified.
This is a device-specific report, not a guarantee for every V7 firmware variant
or proof that every feature and long-running scenario has passed. The checks
below distinguish automated evidence from hardware-dependent regression testing.

## Known issues

Steering-wheel controls (next/previous station and mute) may not work in all
situations, including on otherwise compatible head units. Behavior depends on
the head unit, firmware and how button events are routed. Successful volume
control does not guarantee that station switching or mute will work. Test these
functions separately while parked; support is not guaranteed for every setup.

## Automated test coverage

Unit and emulator tests cover app logic, English as the default language,
custom-image import and removal, logo-resource isolation, translation coverage,
language selection and activity recreation, and translated screen layouts.
Logo-isolation checks verify that the public build has no bundled station-logo
collection. These checks do not verify radio hardware or audible playback.

The public images are AI-assisted illustrations with fictional stations, logos
and track text, not screenshots or test evidence. The head-unit image is a
synthetic composite, not a photograph of the app running in a vehicle.
See [illustration provenance](images/README.md).

## Languages and layout

English is the default; Finnish, German, French, Spanish, Portuguese, Italian,
Swedish, Polish, Dutch, Turkish and Czech are selectable. All 12 languages are
packaged for offline switching. Station names supplied by users or RDS are not
translated. Translations may contain mistakes; native-speaker corrections are
welcome. See [languages and translations](LOCALIZATION.md).

Emulator layout tests cover the main screen, radio/general settings, station
menu, language selection, reception mode, tuning choice and manual tuning.
Dialogs can scroll where needed; long user-supplied station names may
intentionally ellipsize. These tests do not guarantee that every screen size,
font setting or manufacturer layout behaves identically.

Resource coverage and formatting placeholders can be checked with PowerShell:

```powershell
./tools/verify-translations.ps1
```

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
