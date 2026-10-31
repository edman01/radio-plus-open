# Community beta 5 (dev36)

Free, open-source Radio+ for compatible Android head units.
Tested on **Junsun V7**, with support for its newer stock radio app.

## Download and install

- `RadioPlus-0.16.1-dev36.apk`: signed APK without bundled station logos.
- `SHA256SUMS.txt`: checksum for the APK.
- Source archives: source code for this release.

Version: `0.16.1-dev36-play` · version code: `98`.
Package: `fi.radioplus.app.play`.

Requires **Android 8.1+** and a recognized stock radio service.
Keep the original radio app installed and enabled. No firmware changes are needed.
Install and test only while parked.

## What's new

- Added support for Junsun V7 firmware `MQ001_2026.09.18.19.50`, with stock
  `com.hcn.autoradio` version `V.1.0.202609181544-mt8768-beta`.
- Retained support for the previously tested V7 firmware
  `MQ001_2025.12.29.16.58_6_6826_G`.
- Improved playback continuity: reopening Radio+ can reuse confirmed ongoing
  radio playback without an extra audio handoff.
- Corrected the HCN radio callback contract.
- Retained the single-press steering-wheel and on-screen station-change fixes.
- Included the existing experimental older HCN, TS, K4811 MCU, G5 MCU and SPD
  profiles in this APK. Their support remains unverified on real head units.
- Kept the interface and custom-logo import unchanged. No station logos are bundled.

The owner reports dev36 working on the updated V7. This is not a separate
confirmation of every function. The original tested firmware reports Android
13 / API 33 despite an Android 15 label in OEM settings; the updated firmware's
API level has not been established here.

## Features

- 12 languages: English, Finnish, German, French, Spanish, Portuguese, Italian,
  Swedish, Polish, Dutch, Turkish and Czech.
- Station lists, favorites and long-press editing.
- Custom PNG/JPEG logo import and removal.
- Scrollable dialogs and improved text wrapping.

## Known limitations

Only the Junsun V7 has physical-device confirmation. AM, steering-wheel mute,
sleep/wake and individual functions on the updated firmware need further testing.
Experimental TS profiles do not provide automatic scan or LOCAL/DX controls.
K4811 MCU, G5 MCU and SPD profiles do not provide automatic scan, station seek
or preset preview, and native steering buttons may follow the stock radio
rather than Radio+'s stations/favorites. Exact profile requirements and audio
limitations are listed in the [experimental guide](https://github.com/edman01/radio-plus-open/blob/main/docs/EXPERIMENTAL.md).
[Testing and known issues](https://github.com/edman01/radio-plus-open/blob/main/docs/TESTING.md).

Station logos are not included. [Import your own logos](https://github.com/edman01/radio-plus-open/blob/main/docs/LOGOS.md).
[Installation guide](https://github.com/edman01/radio-plus-open/blob/main/docs/INSTALL.md) ·
[MIT license and third-party notices](https://github.com/edman01/radio-plus-open/blob/main/THIRD_PARTY_NOTICES.md).
