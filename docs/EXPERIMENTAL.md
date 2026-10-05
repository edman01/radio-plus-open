# Experimental APK builds

An optional test build with support for additional Junsun radio implementations.
**The additional radio support has not been tested on real head units.**

## Changes in 0.16.1-dev28

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

- `RadioPlus-experimental.apk`: signed APK without bundled station logos.
- `SHA256SUMS.txt`: checksum for the APK.

Installed version: `0.16.1-dev28-play` · version code: `90`.
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

Automatic scanning and LOCAL/DX are unavailable on the AC8259, 825X and 8667Q
profiles. Manual tuning and Radio+'s favorites are implemented, but real audio,
pause/resume and steering-button behavior still need device testing.
Xtrons and ATOTO support is not included. Widget support is not available yet.

See [compatibility and limitations](https://github.com/edman01/radio-plus-open/blob/main/docs/COMPATIBILITY.md).

## Test and report

Test only while parked, at a low volume. Start with manual FM tuning and audible
playback, then try favorites, pause/resume, steering buttons and switching audio
sources. **Settings → General → About app** shows the detected radio.

[Report your results](https://github.com/edman01/radio-plus-open/issues/new?template=compatibility.yml)
with the head unit, firmware, app version and what works or does not.
Do not post personal information, device identifiers, full logs or firmware files.
