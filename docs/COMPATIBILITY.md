# Compatibility

Radio+ has been tested on the **Junsun V7 running Android 13**.
Compatibility with other head units depends on their stock radio software and firmware.
Keep the original radio app installed and enabled; no firmware changes are needed.

## Tested and working

**Junsun V7 / Android 13 (API 33)** — radio playback and next/previous
steering-wheel controls confirmed on the maintainer's unit.
See [device details](../README.md#compatibility) and [known issues](TESTING.md#known-issues).

## Experimental support

**Experimental support — not yet tested on a real head unit.**
Available in [Experimental APK builds](https://github.com/edman01/radio-plus-open/releases/tag/v0.16.1-experimental3),
not the community beta 4 download. Read the [installation notes](EXPERIMENTAL.md#download)
before trying it.

| Head unit / platform | Firmware version | Stock radio package |
| --- | --- | --- |
| Junsun V1 Pro C / MT8163 | AJ `2023.12.18.16_3` | `com.hcn.autoradio` |
| Junsun V1 Pro / AC8259 | UI02 V115 `20240307` | `com.ts.MainUI` |
| Junsun 825X Pro (reported as 8257p) | UI02 V27 `20211201` | `com.ts.MainUI` |
| Junsun / 8667Q | UI02 V23 `20221121` | `com.ts.MainUI` |
| K4811, MCU tuner type 0 only | `K4811_NWD_S212851.20260507.044024`, RadioService 2.3.0 + KernelService 2.5.0 | `com.nwd.radio.service` + `com.nwd.kernel` |

Support currently covers the inspected stock radio apps from these firmware versions.
Other versions may not be recognized. A matching model or package name alone
does not guarantee compatibility.

Automatic scanning and LOCAL/DX controls are not yet available on the AC8259,
825X and 8667Q versions. Manual tuning and Radio+'s own favorites are implemented;
audio playback, pause/resume and physical steering buttons still need device testing.
Use Experimental 2 (`0.16.1-dev29`) or newer: Experimental 1 had an incorrect
TS tuning parameter. See the [changes and remaining limitations](EXPERIMENTAL.md).

The K4811 profile in Experimental 3 is a touch-control test, **not full V7-equivalent
support**. Radio+ wheel integration is unavailable; native buttons may still
operate the stock tuner. K4811 MCU automatic scan, station seek and preset preview
are unavailable. AM needs a valid device grid. K4811 starts from Android audio or
the current radio source, not Bluetooth/other dedicated hardware sources.
Read the [complete limitations](EXPERIMENTAL.md#k4811-limitations) before installing.

### G5 development profile

Source version **0.16.1-dev32** additionally recognizes the inspected
`G5_NWD_S212851.20260916.201422` RadioService **2.4.2** + KernelService **2.6.2**
pair, **MCU / runtime tuner type 0 only**. This profile is not in the Experimental 3
download. Manual FM, conditional AM, Radio+ stations/favorites, LOCAL/DX and
source-based play/pause have synthetic contract tests, not physical device confirmation.
ARM/Allwinner and other runtime types remain disabled.

G5 has the same touch-control limitations as K4811 MCU. Native steering controls
remain with the stock firmware, not Radio+'s favorites order. A Hizpo/Asuret QS
name or the `com.nwd.radio` UI package alone does not establish this exact profile.
See [G5 scope and limitations](EXPERIMENTAL.md#development-build-0161-dev32--g5-mcu).

## Under investigation

- Xtrons IAP12CTS / IA series
- ATOTO A7 / HN7
- Junsun V3 Pro / MT8768
- NWD / K2401 Allwinner: RadioService 2.2.2 + KernelService 2.2.6
- Other NWD service versions, K4811/G5 tuner types 1/2/3, and Hizpo / `com.nwd.radio` variants outside the exact pairs above

Support has not been enabled for these models or variants. Original radio APKs,
matching firmware or further device evidence may be needed to continue.
Do not install another model's firmware to try Radio+.

The inspected NWD firmware directs wheel keys to the stock radio's station search;
Radio+ does not substitute its own next/previous list navigation on that route.
The enabled functions have synthetic protocol tests, not physical hardware verification.
See [experimental support](EXPERIMENTAL.md).
The visible `com.nwd.radio` package name alone is insufficient to identify a
compatible service pair, firmware or tuner implementation.

## Request support

An original stock radio APK or firmware for your exact model can help us
identify how to control its tuner. This helps us investigate; it does not
guarantee that support can be added.

Open an issue with your model, firmware version and stock radio package.
Include an official firmware download link if available, or say whether you
have the original radio APK. Do not attach manufacturer APKs, firmware or device
dumps to a public issue; ask how to provide the relevant files separately.

## Report your results

[Report compatibility](https://github.com/edman01/radio-plus-open/issues/new?template=compatibility.yml)
with your head unit model, firmware, Radio+ version, stock radio package/version,
and what works or does not. Development builds show the detected radio under
**Settings → General → About app**.
Do not include device identifiers or account information.

[Back to Radio+](../README.md)
