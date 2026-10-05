# Radio backends and experimental compatibility

Retail names such as **Junsun V1** do not identify one radio implementation.
Different units sold under that name can use different processors, firmware and
radio services. An Android version label or the package name alone is not enough
to select a safe control method.

## Automatic selection in development builds

Development source **0.16.1-dev28** inspects the installed stock radio APK locally,
off the UI thread, and matches its SHA-256 fingerprint to an inspected contract.
It then checks the connected Binder's interface descriptor, including the nested
radio Binder on TS systems. Both the screen's
state reader and the background playback service use the same selection.
The detector does not load or execute the stock APK, and nothing is uploaded
during detection.

This is intentionally conservative: a different APK, including a newer firmware
version with the same package name, is **unrecognized until inspected**. Radio+
does not guess a profile or probe unknown command numbers. Unknown APKs are not
bound or controlled. **Settings → About** shows the selected profile, stock app
version and APK fingerprint for a compatibility report. Detection identifies a
control contract; it does not prove audible playback or steering-key delivery.

| Inspected stock radio | Selected method | Evidence and status |
| --- | --- | --- |
| Junsun V7 sample, `com.hcn.autoradio`, `V.1.0.2512261203` | Current HCN, 31 transactions | This unit is the existing physical test device. The new selector still needs a parked-device regression test. |
| Junsun V1 Pro C MT8163 sample, AJ firmware `2023.12.18.16_3`, `com.hcn.autoradio` | Legacy HCN, 25 transactions | Experimental adapter; static APK inspection and synthetic Binder tests only. No physical V1 confirmation. |
| Junsun V1 Pro AC8259 sample, UI02 V115 `20240307`, `com.ts.MainUI` | TS common service and nested radio Binder | Experimental core tuning/source adapter; static inspection and synthetic tests only. |
| Junsun 825X Pro sample, owner-reported V1 Pro / 8257p, UI02 V27 `20211201`, `com.ts.MainUI` | TS common service and nested radio Binder | Exact sample only; the archive is labelled 8259. Not proof of all 8257 devices. No physical confirmation. |
| Junsun 8667Q sample, UI02 V23 `20221121`, `com.ts.MainUI` | TS common service and nested radio Binder | Experimental core tuning/source adapter; no physical confirmation. |

The recognized APK fingerprints are listed in
[RadioBackendProfile](../app/src/main/java/fi/radioplus/app/RadioBackendProfile.java).
The released **community beta 4** predates this selector and the experimental adapters;
its downloadable APK has not been replaced by the development build.

## Why the legacy radio needs another method

Both HCN samples expose `FMPlugService` and the same AIDL interface descriptor,
but the methods have different transaction numbers from number 17 onward. For
example, **25 reads scan state on the V7 API but requests audio playback on the
legacy API**. A trial call is therefore not a safe way to detect a radio.

The legacy adapter maps band/frequency reads, direct tuning, seeking, scanning
and playback requests to the inspected older contract. Radio+'s own favorites
remain available; OEM favorite synchronization and separate OEM audio-focus
endpoints are absent and are not called. RDS and mute/source handling also depend
on the firmware's optional framework interfaces and need device testing.

The MT8163 sample came from a firmware described by its owner in this
[Junsun V1 Pro C recovery video](https://www.youtube.com/watch?v=MEyULwFnaDs).
That provenance does not establish compatibility with every V1 Pro C.

## Experimental TS MainUI support

The three fingerprinted TS samples export `com.ts.main.common.MainUI` in
`com.ts.MainUI`. Radio+ verifies `ITsCommon`, requests its `Radio` Binder, then
verifies `ITsRadioCommon`. It does not send HCN transactions to this service.
The inspected contracts share the narrow methods used by this adapter; unrelated
MainUI APKs with the same package or version label are not automatically accepted.

Implemented operations are band/frequency reads, validated direct FM/AM tuning,
manual frequency steps, seeking, RDS station-name reads, and radio source
selection. The adapter converts the OEM FM unit (10 kHz) to Radio+'s kHz unit
and maps the OEM FM/AM banks to the app's lists. Next/previous media callbacks
still use Radio+'s own station/favorite order.

Play requests the stock radio source; Pause exits that source only if it still
belongs to radio. Neither operation toggles global mute or opens the stock UI.
This follows inspected source-selection commands, **not verified speaker output**.
The HCN-specific PCM media-routing workaround is not applied to TS: physical
steering-key delivery, source handoff, interruptions and pause/resume need tests
on each unit. A synthetic media callback test does not prove steering keys work.

Automatic scan and LOCAL/DX controls are disabled because their complete state
and control behavior has not been verified. Radio+'s own favorites remain usable;
OEM preset/favorite synchronization, separate OEM audio-focus calls and extra
framework metadata are not promised. Unknown features never fall back to guessed
transaction numbers, native-library loading, reset commands or global mute.

The AC8259 sample was located through its owner's
[V1 Pro recovery research](https://github.com/initialChris/junsun-v1pro-recovery).
The V27 and 8667Q V23 samples are linked by an
[unofficial Junsun owner-group firmware index](https://telegra.ph/Grupo-Espa%C3%B1ol----No-oficial----Radio-Junsun-03-21).
These are sample provenance, not manufacturer certification or firmware-install
recommendations. No firmware needs to be flashed to use a matching installed APK.

## Next research candidates

| Candidate | What has been found | What is still needed |
| --- | --- | --- |
| Other Junsun AC8259 / MTK8257 / 8667Q firmware versions | The exact V115, V27 and V23 samples above have experimental profiles. | Each different stock APK must be inspected and fingerprinted before enabling control; physical tests are still needed. |
| Xtrons IAP12CTS / IA series | IA-series owner documentation uses `8667.bin` firmware; owner reports link IA firmware received from Xtrons support. This is a research lead, not an inspected IAP12CTS APK. | The exact model's original MainUI/Radio APK or accessible matching firmware. Located IA download shares are no longer accessible. No Xtrons profile has been enabled. |
| ATOTO A7 / HN7 / MQ001 | Platform research suggests a potentially related HCN family. No genuine Radio APK was obtained from the located downloads. | Its original Radio APK and exported service contract; matching chipset/platform is not a compatibility guarantee. |
| Junsun V3 Pro MT8768 | A seller's platform listing is a research lead, not an inspected radio contract. | An exact-model firmware and original Radio APK. The V7 chipset alone does not establish compatibility. |

The Xtrons lead comes from the
[IA/IX firmware documentation](https://xtrons.ibus-app.de/index.php?title=Firmwareupdate)
and an [IA owner's support-firmware report](https://www.ford-forum.de/threads/android-carplay-multimedia-nachruesten.187074/page-2).
It does not establish compatibility with other Xtrons families such as PQ/PQS/IX.
The ATOTO lead is a firmware researcher's
[A7EG211PKLB / HN7 analysis](https://www.reddit.com/r/ATOTO/comments/1u8d39b/unlocking_the_atoto_a7_a7eg211pklb_hn7_settings/),
not an independently inspected Radio APK.
ATOTO's [firmware page](https://atoto.jp/pages/firmware-updates) directs users to
support while direct downloads are unavailable; a CAN APK is not a Radio APK.
The V3 Pro lead is a
[Junsun seller's model/platform listing](https://junsun.de/autoradios-navi/fur-chrysler/chrysler-300c-android-radio-gps-navi-carplay-bluetooth-dab/).
No firmware needs to be flashed for this research. Do not install firmware from
another model to try to make Radio+ work.

Please [report your unit](https://github.com/edman01/radio-plus-open/issues/new?template=compatibility.yml)
with brand/model, firmware, Radio+ version and the stock radio package/version.
Do not publish device identifiers, account data or unredacted device logs.
