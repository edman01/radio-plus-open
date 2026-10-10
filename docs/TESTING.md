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
Results apply to this tested unit; other firmware versions may behave differently.

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
Experimental 2 adds regression tests for TS frequency-to-step conversion,
invalid or changing tuning grids, cancellation during band changes, on-screen
seek and step directions, access to manual tuning when automatic scanning is
unavailable, and older HCN frameworks with a mute setter but no getter.
NWD development adds tests for service-pair recognition, Binder reply
layout, FM/AM units, tuning-grid checks, canceled band changes, band-change
cooldown, scan-bank collection and restoration, source-request coalescing,
pause during startup, and explicit media transport next/previous without HCN workarounds.
These use synthetic services, not manufacturer code or physical radio hardware.
FM/AM checks exercise the existing band button and manual-tuning selectors,
frequency limits and step wrapping, return to FM, and an FM-only endpoint.
Audio tests cover source ownership and pending pause/play; passive polling and
post-tune completion must never take playback back from another app. K4811 MCU
starts only from Android audio or the current radio source. Additional MCU tests
cover the one-way tuning call, actual-grid stepping, unsupported feature rejection,
unresolved commands, delayed source replies and old commands after reconnection.
The inspected NWD firmware sends native wheel keys to stock station search in
FM mode. Android media-control tests do not validate or replace that route;
NWD is offered as experimental touch-control support, not Junsun V7-equivalent support.
Experimental 3 enables only K4811 MCU/type 0. Allwinner adapter tests do not mean
that its development profile is enabled in the released APK.
Development version 0.16.1-dev32 adds the separately inspected G5 MCU service pair.
G5 tests cover exact pair recognition, read-only rejection of unsupported runtime
types, one-way tuning/readback, FM/AM grids, source handoff, rapid play/pause,
unsupported scan rejection and isolation from native raw media-key events.
Development version 0.16.1-dev33 also tests manual FM/AM catalog saving, duplicate
prevention, preserved station names/logo references/favorites, and station-list
reload after reopening the Activity. Same-frequency Tune delivers fresh readback;
periodic polls remain deduplicated, and stale binding deliveries are discarded.
These are emulator tests, not proof of process-death persistence or radio reception.
They use synthetic Binder endpoints; the firmware is inspected, not
installed or executed in the emulator. These checks do not prove a device's
installed services or runtime tuner match this profile.
See [supported models and limitations](COMPATIBILITY.md).

Media-control tests cover station-list and favorites order, single-press handling,
Android media-key routing, and avoiding a repeated audio handoff during adjacent
station changes and direct on-screen station selection. They also cover resuming
from pause, explicit audio takeover, and preserving playback or pause when
reopening the app. About-app tests cover the displayed version, project link,
and visible Back navigation between settings and its subdialogs.

Documentation images are [illustrations](images/README.md), not hardware test evidence.

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

An emulator has no analog tuner. UI tests and simulated radio data cannot verify
reception or speaker audio on a head unit.

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
