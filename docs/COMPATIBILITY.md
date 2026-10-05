# Radio backends and experimental compatibility

Retail names such as **Junsun V1** do not identify one radio implementation.
Different units sold under that name can use different processors, firmware and
radio services. An Android version label or the package name alone is not enough
to select a safe control method.

## Automatic selection in development builds

Development source **0.16.1-dev27** inspects the installed stock radio APK locally,
off the UI thread, and matches its SHA-256 fingerprint to an inspected contract.
It then checks the connected Binder's interface descriptor. Both the screen's
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

The recognized APK fingerprints are listed in
[RadioBackendProfile](../app/src/main/java/fi/radioplus/app/RadioBackendProfile.java).
The released **community beta 4** predates this selector and experimental adapter;
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

## Next research candidates

| Candidate | What has been found | What is still needed |
| --- | --- | --- |
| Junsun V1 Pro AC8259 / MTK8259 | V115 OTA extracted; `com.ts.MainUI` APK inspected. It exports TS speech-radio and common-radio Binder interfaces, including frequency reads and tuning. | A separate TS adapter, verified frequency/band conversion, audio-source behavior and physical tests. HCN calls must not be used. |
| Junsun V1 Pro MTK8257 | Firmware-family references alongside 8259. | The exact unit's stock APK; do not assume the 8259 contract is identical. |
| Junsun V1 / V1 Pro 8667Q | Firmware and MainUI modification leads from a developer working on these units. | An original stock APK from the exact firmware, followed by contract comparison and device tests. Modified APKs are not treated as stock evidence. |
| ATOTO A7 / HN7 / MQ001 | Platform research suggests a potentially related HCN family. | Its original Radio APK and exported service contract; matching chipset/platform is not a compatibility guarantee. |
| Junsun V3 Pro MT8768 | A seller's platform listing is a research lead, not an inspected radio contract. | An exact-model firmware and original Radio APK. The V7 chipset alone does not establish compatibility. |

The AC8259 OTA was located through its owner's
[V1 Pro recovery research](https://github.com/initialChris/junsun-v1pro-recovery).
The 8667Q lead is the developer's
[Junsun/MainUI channel](https://t.me/s/junsunv1?before=28).
The ATOTO lead is a firmware researcher's
[A7EG211PKLB / HN7 analysis](https://www.reddit.com/r/ATOTO/comments/1u8d39b/unlocking_the_atoto_a7_a7eg211pklb_hn7_settings/),
not an independently inspected Radio APK.
The V3 Pro lead is a
[Junsun seller's model/platform listing](https://junsun.de/autoradios-navi/fur-chrysler/chrysler-300c-android-radio-gps-navi-carplay-bluetooth-dab/).
No firmware needs to be flashed for this research. Do not install firmware from
another model to try to make Radio+ work.

Please [report your unit](https://github.com/edman01/radio-plus-open/issues/new?template=compatibility.yml)
with brand/model, firmware, Radio+ version and the stock radio package/version.
Do not publish device identifiers, account data or unredacted device logs.
