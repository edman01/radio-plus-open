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
Available in [Experimental APK builds](https://github.com/edman01/radio-plus-open/releases/tag/v0.16.1-experimental1),
not the community beta 4 download. Read the [installation notes](EXPERIMENTAL.md#download)
before trying it.

| Head unit / platform | Firmware version | Stock radio package |
| --- | --- | --- |
| Junsun V1 Pro C / MT8163 | AJ `2023.12.18.16_3` | `com.hcn.autoradio` |
| Junsun V1 Pro / AC8259 | UI02 V115 `20240307` | `com.ts.MainUI` |
| Junsun 825X Pro (reported as 8257p) | UI02 V27 `20211201` | `com.ts.MainUI` |
| Junsun / 8667Q | UI02 V23 `20221121` | `com.ts.MainUI` |

Support currently covers the inspected stock radio apps from these firmware versions.
Other versions may not be recognized. A matching model or package name alone
does not guarantee compatibility.

Automatic scanning and LOCAL/DX controls are not yet available on the AC8259,
825X and 8667Q versions. Manual tuning and Radio+'s own favorites are implemented;
audio playback, pause/resume and physical steering buttons still need device testing.

## Under investigation

- Xtrons IAP12CTS / IA series
- ATOTO A7 / HN7
- Junsun V3 Pro / MT8768

Support has not been added for these models. Their original radio APK or matching
firmware is needed to continue. Do not install another model's firmware to try Radio+.

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
