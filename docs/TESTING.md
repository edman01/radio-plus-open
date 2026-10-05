# Beta testing and known limitations

## Physical device testing

The maintainer reports Radio+ confirmed working on their physical **Junsun V7 running Android 13**.
Next/previous steering-wheel controls were confirmed working with the correction
included in community beta 4. One press changes one station, and the brief audio
interruption after the new station starts has been resolved on this tested unit.
The optional accessibility service was not needed for these next/previous buttons.
On-screen station selection was also confirmed without the delayed interruption.
Playback continuity when reopening the app still needs verification on the head unit.
Other testing has been emulator-only; no other head-unit model is verified.
This is a device-specific report, not a guarantee for every V7 firmware variant
or proof that every feature and long-running scenario has passed. The checks
below distinguish automated evidence from hardware-dependent regression testing.

## Known issues

Next/previous steering-wheel controls are fixed on the tested Junsun V7 / Android
13 configuration. Other head units and firmware variants remain unverified.
Steering-wheel mute behavior needs separate testing. Successful volume control
does not establish compatibility with every steering command. Behavior depends
on how the firmware routes button events; test while parked.

## Automated test coverage

Unit and emulator tests cover app logic, English as the default language,
custom-image import and removal, logo-resource isolation, translation coverage,
language selection and activity recreation, and translated screen layouts.
Logo-isolation checks verify that the public build has no bundled station-logo
collection. These checks do not verify radio hardware or audible playback.

Experimental radio support has automated tests for radio detection, frequency
handling and playback commands. It has not been tested on real head units.
See [supported models and limitations](COMPATIBILITY.md).

Media-control tests cover station-list and favorites order, single-press handling,
Android media-key routing, and avoiding a repeated audio handoff during adjacent
station changes and direct on-screen station selection. They also cover resuming
from pause, explicit audio takeover, and preserving playback or pause when
reopening the app. About-app tests cover the displayed version, project link,
and visible Back navigation between settings and its subdialogs.

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
menu, language selection, reception mode, steering settings, diagnostics,
About app, tuning choice and manual tuning.
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
   Also leave and reopen Radio+ while its radio is playing, and repeat while
   intentionally paused. Reopening should preserve that state without a new
   audio interruption.
4. Test manual FM and AM tuning, saving/renaming and app restart. Test scanning
   only where enabled; experimental TS profiles intentionally disable scanning
   and LOCAL/DX controls.
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
