# Experimental APK builds

An optional test build with support for additional Junsun radio implementations.
**The additional radio support has not been tested on real head units.**

## NWD development status — not an APK release

- Added an NWD / K2401 Allwinner adapter for the inspected RadioService 2.2.2
  and KernelService 2.2.6 pair. Both installed APK fingerprints and the runtime
  tuner type must match before control is enabled.
- Implemented manual FM/AM tuning, station seek, Radio+ favorites, RDS station
  names and RadioText, LOCAL/DX, and radio-source play/pause for that adapter.
- Added automatic-scan control and collection of all FM/AM preset banks, with
  restoration of the selected station. The stock service's band-change delay
  is respected. Scan-result collection temporarily tunes the other banks.
  Ambiguous default-filled banks are rejected: the service does not expose a
  reliable station count. Existing stations are retained, and scan progress
  frequencies are not imported as found stations. Automatic scan is not yet
  equivalent to the tested Junsun V7 implementation: partial banks and some
  genuine full scans cannot be accepted without reliable result metadata.
- Band changes are checked before tuning, and frequencies are checked against
  the stock tuner grid. Stopped or canceled commands do not continue tuning.
- Coalesced pending NWD playback requests to avoid repeated initialization.
  Once the radio source is acknowledged, a later explicit Play after another
  app is treated as a new request, including quick switches between apps.
  NWD does not use the Junsun V7 audio-focus, input-gain or PCM handoff workarounds.
- Shared the tuner connection between the app screen and background playback
  to prevent overlapping bank changes and station restoration. Tuning now waits
  for a queued scan to start before canceling it; an unconfirmed start cannot
  be reported as a successful stop.
- Kept app navigation responsive during slow status reads and discarded status
  or preset results belonging to an earlier connection.
- Fixed explicit Play immediately after Pause being ignored while the source
  setting still reports radio. Reading source status never reclaims playback.
- Added tests for the NWD protocol, malformed replies, source switching,
  pause during startup, and next/previous through Android media controls.
  UI tests cover the FM/AM band cycle and direct FM/AM choices, restoration of
  each band's frequency, MHz/kHz display and refusal to tune AM on an FM-only tuner.
- Kept the existing interface, layouts, themes and translations unchanged.

This is **not confirmed support for every Hizpo or `com.nwd.radio` device**.
The enabled development profile remains the exact RadioService 2.2.2 /
KernelService 2.2.6 pair with the verified runtime tuner type. Firmware samples
for K2401 (RadioService 2.1.8) and K4811 (RadioService 2.3.0 / KernelService 2.5.0)
have also been inspected, but are not enabled by this profile. K4811 changes
the tuning call and contains several different tuner implementations; its
processor or model name alone cannot select a safe control method.
No manufacturer APKs or firmware are included.

**Not ready for Junsun V7-equivalent support. No NWD APK has been released.**
The inspected K2401 and K4811 paths forward wheel next/previous to the stock radio's station
search while the FM source is active, rather than to Android media controls.
Radio+'s list navigation does not yet replace that native key route. An additional
receiver would also leave the stock handler active, risking conflicting actions.
Passing Android media-control tests does not validate this firmware key route.
Native next/previous must move exactly one station in Radio+'s selected list,
without a competing stock-radio search. This is a requirement for the NWD APK
release, not an optional feature to be left out.

Audible playback, pause/resume, scan results, source handoff and ACC wake still
need testing on hardware. Manual AM tuning requires the stock service to report
a usable AM grid. The exact model and matching service APKs are still needed
from the reported Hizpo device; this sample does not establish its compatibility.
The download below remains Experimental 2 and does not include the NWD adapter.

## Changes in 0.16.1-dev29 — Experimental 2

- Fixed AC8259, 825X and 8667Q tuning: the stock command takes a tuning-step
  index, not an absolute frequency. Radio+ now reads the stock frequency grid
  and checks the conversion before sending a tuning command.
- Canceled band changes no longer continue tuning after playback is stopped.
- Fixed older HCN pause/resume when the framework provides a mute command
  but no method for reading the mute state.
- Restricted the V7 input-gain workaround to its HCN interface. It is not
  applied to TS radios or before the stock radio has been identified.
- Corrected TS seek and step directions to match the on-screen controls.
- The TS tuning menu offers manual tuning without the unavailable automatic
  scan option. Controls stay disabled until a supported radio is identified.
- Added the missing radio-detection and compatibility messages in all 12 languages.

This replaces Experimental 1 (`0.16.1-dev28`), which sent the wrong tuning
parameter to TS radios. **TS testers should use Experimental 2 or newer.**

## Radio interfaces added in Experimental 1

- Added an experimental interface for the older `com.hcn.autoradio` version
  inspected on the Junsun V1 Pro C / MT8163.
- Added experimental `com.ts.MainUI` interfaces for the inspected AC8259,
  825X and 8667Q firmware versions listed below.
- Added automatic selection of the radio control method for recognized stock
  radio APKs, with service-interface checks before use.

These additions have passed automated tests and Android emulator checks.
Actual tuning, audible playback and steering-button operation still need
verification on the listed head units.

## Download

- `RadioPlus-0.16.1-experimental2.apk`: signed APK without bundled station logos.
- `SHA256SUMS.txt`: checksum for the APK.

Installed version: `0.16.1-dev29-play` · version code: `91`.
Package: `fi.radioplus.app.play`.

Requires Android 8.1+ and a recognized stock radio app.
Keep the original radio app installed and enabled. No firmware changes are needed.

This APK updates the community app rather than installing alongside it.
Older community APKs cannot be installed over its higher version code through
the normal installer. Uninstalling removes saved stations, logos and settings.
If you want to keep using the confirmed Junsun V7 build, stay on
[community beta 4](https://github.com/edman01/radio-plus-open/releases/tag/v0.16.1-community-beta4).

## Experimental radio support

| Head unit / platform | Inspected firmware |
| --- | --- |
| Junsun V1 Pro C / MT8163 | AJ `2023.12.18.16_3` |
| Junsun V1 Pro / AC8259 | UI02 V115 `20240307` |
| Junsun 825X Pro (reported as 8257p) | UI02 V27 `20211201` |
| Junsun / 8667Q | UI02 V23 `20221121` |

Radio+ automatically selects the control method for a recognized stock radio APK.
A matching model or package name alone is not enough: other firmware versions
may remain unrecognized.

For NWD research, `com.nwd.radio` is the stock interface, not the service that
controls the tuner. Its matching `com.nwd.radio.service` and `com.nwd.kernel`
are also needed. Do not install stock apps from another head unit to force a match.

Automatic scanning and LOCAL/DX are unavailable on the AC8259, 825X and 8667Q
profiles. Manual tuning and Radio+'s favorites are implemented, but real audio,
pause/resume and steering-button behavior still need device testing.
The scan command has been identified, but reliable retrieval of all found
frequencies is not yet implemented. A verified LOCAL/DX command is also missing.
These profiles do not yet offer the full Junsun V7 feature set.
Radio+'s tuning range remains FM 87.5–108.0 MHz in 100 kHz steps and AM
522–1620 kHz in 9 kHz steps. Other regional ranges and spacings are not supported;
TS tuning rejects a requested frequency if it is absent from the stock radio's grid.
Xtrons and ATOTO support is not included.

See [compatibility and limitations](https://github.com/edman01/radio-plus-open/blob/main/docs/COMPATIBILITY.md).

## Test and report

Test only while parked, at a low volume. Start with manual FM tuning and audible
playback, then try favorites, pause/resume, steering buttons and switching audio
sources. **Settings → General → About app** shows the detected radio.

[Report your results](https://github.com/edman01/radio-plus-open/issues/new?template=compatibility.yml)
with the head unit, firmware, app version and what works or does not.
Do not post personal information, device identifiers, full logs or firmware files.
