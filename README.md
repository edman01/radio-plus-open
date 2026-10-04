# Radio+

Free, open-source FM/AM radio tested on the **Junsun V7 running Android 13**, with favorites and custom logos.
May also work on other Chinese Android head units with a compatible stock radio service;
compatibility with those devices has not been verified.

**[Download beta APK](https://github.com/edman01/radio-plus-open/releases)**

![Radio+ on a head unit](docs/images/radio-plus-head-unit-demo.png)

*AI-assisted illustration with fictional stations and logos. Logos are not included in the app.*

## Compatibility

Android 8.1+ and a compatible stock radio with **FMPlugService** are required.
**Keep the stock radio installed and enabled.** Radio+ does not replace it or change the firmware.

Radio+ controls the stock `com.hcn.autoradio` app through its **FMPlugService**.
Finding `com.hcn.autoradio` on your head unit is a promising compatibility clue:
Radio+ **may** work, but the package name alone is not a guarantee. The firmware
must still expose a compatible service/API.

**Check your stock radio's package name (no PC needed):**

1. Open Android **Settings → Apps**, show all apps or system apps, then select
   the original **Radio** app and open **App info**. Menu names vary by firmware.
2. Look for the **package identifier**, not the display name “Radio” or the
   device's build number. Some firmware does not show it here; if yours does not,
   inspect the original Radio app with an already-installed app-information tool,
   such as DevCheck. The expected identifier is exactly `com.hcn.autoradio`.

You do not need to replace, disable or uninstall the stock radio to check this.
Optional, with an already-authorized ADB connection:
`adb shell pm list packages com.hcn.autoradio` should include `package:com.hcn.autoradio`.

**Tested and working on the maintainer's unit:**

| Detail | Reported specification |
| --- | --- |
| Device / model / board | Junsun V7 / `tb8768p1_64_bsp` |
| Chipset | MediaTek MT8768V/CX (reported as MT6765) |
| Memory / storage / display | 6 GB RAM / 128 GB storage / 1280 × 720 |
| Firmware | `MQ001_2025.12.29.16.58_6_6826_G` |
| MCU | `MQ001-25.08.01_499` |
| Android | Android 13 (API 33), observed by DevCheck and diagnostics |

The OEM settings screen displays **Android 15**, but DevCheck and diagnostics
report **Android 13 / API 33**; the OEM label is not evidence of Android 15 support.
Physical testing is limited to this unit; other testing is emulator-only. Other
models and firmware variants remain unverified, and not every feature is guaranteed.
See [test details and known issues](docs/TESTING.md).

## How it works

Radio+ provides an alternative interface for the head unit's built-in FM/AM tuner.
It sends tuning and playback commands to the compatible stock radio service,
which controls the radio hardware. It is not an internet radio player.

The original radio app must stay installed and enabled, but its interface does
not need to remain open. [How tuning, audio and station information work](docs/HOW_IT_WORKS.md).

## Community compatibility reports

Tried Radio+ on your head unit? [Report your results](https://github.com/edman01/radio-plus-open/issues/new?template=compatibility.yml)
or [browse user reports](https://github.com/edman01/radio-plus-open/issues?q=is%3Aissue%20label%3Acompatibility).
Include the model, firmware and what works or does not. Reports are user-submitted,
not a compatibility guarantee for every unit or firmware version.

## Known issues

Next/previous steering-wheel controls are fixed and confirmed working on the
tested **Junsun V7 running Android 13** in community beta 4: one press changes one
station, without the subsequent brief audio interruption.
Other head units, firmware variants and steering-wheel mute behavior still need
separate verification. [Details](docs/TESTING.md#known-issues).

## Using Radio+

Open **Tuning** to find stations. Hold a station to manage favorites, rename it or change its logo.

12 languages: English (default), Finnish, German, French, Spanish, Portuguese,
Italian, Swedish, Polish, Dutch, Turkish and Czech.
Choose yours in **Settings → General → Language**.

To import a logo: **Change logo → Add custom logo from device…** → choose a PNG/JPEG from USB or your device.
Use images you have permission to use. [Logo guide](docs/LOGOS.md).

[Install and update](docs/INSTALL.md) · [Build from source](docs/BUILD.md) · [Report a bug](CONTRIBUTING.md)

Independent project, not affiliated with Junsun or Škoda. Configure only while parked.
[MIT license](LICENSE) · [Privacy](docs/PRIVACY.md) · [Third-party notices](THIRD_PARTY_NOTICES.md)
