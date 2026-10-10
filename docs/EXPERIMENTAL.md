# Experimental APK builds

Optional test builds for additional radio implementations.
**This additional support has not been tested on real head units.**
Keep the original radio app and its services installed and enabled.

## Development build 0.16.1-dev33 — G5 MCU

The current source adds a separately recognized **G5 MCU / tuner type 0**
profile for RadioService **2.4.2** and KernelService **2.6.2**, inspected in
`G5_NWD_S212851.20260916.201422`. This is not included in the Experimental 3
download below and has not been verified on a physical head unit.

- Both installed service APK fingerprints must match the inspected pair.
  The runtime tuner type is checked before tuner or audio commands are sent.
  A matching version number, radio UI or Hizpo/Asuret QS model name alone is
  not enough to identify a supported device.
- Includes manual FM tuning, conditional AM, Radio+ stations/favorites,
  LOCAL/DX readback and source-based play/pause. The existing interface is unchanged.
- Uses one-way tuning with frequency/band confirmation, without replaying an
  unconfirmed command or reopening audio after every channel change.
- Manual Tune also confirms and saves an already-current frequency. Tests cover
  confirmed FM/AM saves, repeated tuning without duplicate entries, and station-list
  reload when reopening the app screen. Names, imported logo references and favorites
  are preserved; unconfirmed targets are not saved.
- The same [MCU limitations](#k4811-limitations) apply: no automatic scan,
  station seek, preset preview or Radio+ favorites navigation from native wheel keys.
  Firmware wheel actions remain with the stock service; Radio+ reads the resulting
  frequency/band without sending a second tune command.
- ARM/SI47925 type 1 and Allwinner type 2 remain blocked. Their inspected services
  can complete delayed initialization/unmute work after an audio-source change;
  a safe cancellation mechanism has not been established. Other types are blocked too.
- If the pair matches but the tuner type is unsupported, **Settings → General →
  About app** reports that runtime type. Do not replace firmware or stock services
  to force recognition.

This is limited experimental touch-control support, **not full Junsun V7-equivalent
support** or confirmation for all Hizpo units. Speaker audio, reception, physical
wheel behavior and sleep/wake still need testing on the exact matching device.

## Changes in 0.16.1-dev31 — Experimental 3

- Added touch-control support for the inspected **K4811 MCU implementation**:
  RadioService 2.3.0 and KernelService 2.5.0, runtime tuner type 0.
  Includes manual FM tuning, AM when the device reports a valid AM grid,
  Radio+ stations/favorites, LOCAL/DX readback and source-based play/pause.
- Checks both installed service APKs and the runtime tuner implementation.
  Different K4811 service versions or tuner types remain blocked, not guessed.
- Uses the K4811 asynchronous tuning protocol and waits for frequency/band
  readback. An unresolved command is not replayed. Old commands cannot resume
  after reconnection, and finishing a tune does not reclaim another app's audio.
- Other NWD implementations, including the investigated K2401 Allwinner profile,
  remain disabled in this build. The earlier HCN and TS profiles are retained.
- NWD raw media-key handling is disabled: the stock firmware can otherwise
  handle the same press too, or send a Stop key during radio-source selection.
  Radio+'s on-screen controls and explicit media transport controls remain.
- Preserved the existing interface, layouts, themes and translations.
  Unavailable tuner features are disabled in their existing controls.

K4811 is **experimental touch-control support**, not Junsun V7-equivalent
support. Protocol and app-side tests do not establish reception, audible playback,
physical steering controls or reliable wake-from-sleep on a head unit.

## K4811 limitations

The inspected firmware is `K4811_NWD_S212851.20260507.044024`. Its radio service
contains several implementations. Only **MCU / type 0** is enabled; ARM type 1,
Allwinner type 2 and SPRD/MTK type 3 remain unsupported in this service version.
A K4811 model name, processor, package name or version string alone is insufficient.

- **No Radio+ steering-wheel integration.** Native buttons may still seek or
  tune through the stock radio instead of following Radio+'s selected list.
  Enabling the accessibility helper does not provide NWD wheel support.
- **No automatic scanning, station seek or preset preview.** A safe cancel
  operation has not been established for this MCU contract. Radio+ will not
  interrupt an existing stock-radio search by issuing competing tune commands.
- **AM is conditional:** an actual valid AM grid and band confirmation are required.
  Unavailable or incomplete tuner data is rejected rather than replaced with defaults.
- **Start playback from Android audio or an already selected radio source.**
  Direct takeover from Bluetooth/other dedicated hardware sources, or from an
  unknown source, is blocked. Select Android audio in the head unit first.
- Pause/resume requests use the stock source-switching service. Source readback
  is not proof of audible sound. Delayed firmware replies, audio gaps, source
  handoff and ACC/sleep recovery still require a matching physical device test.
- A command without confirmation is left unresolved. Wait for the tuner to
  respond; do not treat an unchanged display as a successful tune.

No firmware replacement, root access, manufacturer APK installation or system
service removal is requested. Do not install another device's stock apps to force a match.

## Other experimental profiles

| Head unit / platform | Inspected firmware / service | Limits |
| --- | --- | --- |
| Junsun V1 Pro C / MT8163 | AJ `2023.12.18.16_3`, older HCN | Hardware audio and buttons unverified |
| Junsun V1 Pro / AC8259 | UI02 V115 `20240307` | No automatic scan or LOCAL/DX |
| Junsun 825X Pro (reported as 8257p) | UI02 V27 `20211201` | No automatic scan or LOCAL/DX |
| Junsun / 8667Q | UI02 V23 `20221121` | No automatic scan or LOCAL/DX |

The NWD UI package `com.nwd.radio` is not the tuner service. The matching
`com.nwd.radio.service` and `com.nwd.kernel` APKs are both required for recognition.
RadioService 2.1.8, the Allwinner 2.2.2 profile and the exact unidentified Hizpo
device reported on Reddit are not enabled by this release. Xtrons and ATOTO
support is not included.

The app's supported range remains FM 87.5–108.0 MHz in 100 kHz steps and AM
522–1620 kHz in 9 kHz steps. Targets must also exist on the stock tuner's grid;
MCU stepping visits their shared valid frequencies. Other regional ranges are
not supported. The same UI on two devices does not prove the same tuner contract.

## Download

[Download Experimental 3](https://github.com/edman01/radio-plus-open/releases/tag/v0.16.1-experimental3)

- `RadioPlus-0.16.1-experimental3.apk`: signed APK without bundled station logos.
- `SHA256SUMS.txt`: checksum for the APK.

Installed version: `0.16.1-dev31-play` · version code: `93`.
Package: `fi.radioplus.app.play`. Requires Android 8.1+ and a recognized stock backend.

This updates the community app rather than installing alongside it. An older
community APK cannot normally be installed over a higher version code.
Uninstalling removes saved stations, logos and settings. To stay on the
hardware-tested Junsun V7 release, use
[community beta 4](https://github.com/edman01/radio-plus-open/releases/tag/v0.16.1-community-beta4).

## Earlier changes

Experimental 2 (`0.16.1-dev29`) corrected TS frequency-to-step conversion,
band-change cancellation, older HCN pause/resume and capability detection.
It restricted the V7 input-gain workaround to HCN and added translated detection
messages. **Do not use Experimental 1 for TS testing:** it used the wrong tuning parameter.

## Test and report

Test while parked, at low volume. Start with manual FM tuning and sound, then
on-screen favorites, pause/resume, FM/AM selection and switching audio sources.
Stop if controls behave unexpectedly. **Settings → General → About app** shows
the app version and detected radio profile.

[Report your results](https://github.com/edman01/radio-plus-open/issues/new?template=compatibility.yml)
with the model, firmware, app version and what works or does not.
Do not post personal information, device identifiers, full logs or firmware files.
See [compatibility](COMPATIBILITY.md) and [test coverage](TESTING.md).
