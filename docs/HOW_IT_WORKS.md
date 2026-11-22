# How Radio+ works

Radio+ is an alternative interface for the head unit's built-in FM/AM receiver.
It is not an internet radio player and does not replace the manufacturer's radio
driver or firmware. This page explains how Radio+ uses the installed radio
service and why that service is required for playback.

## Connection to the stock radio

Radio+ connects to the installed
`com.hcn.autoradio.service.FMPlugService` through Android's Binder/AIDL interface,
which allows apps to send commands to a service in another process. It uses this
service to select frequencies, switch bands, search for stations and request
radio playback.

**The original radio app must remain installed and enabled**, but its interface
does not need to stay open. No firmware changes, root access or system signing
key are required on the tested configuration.

The connection is defined in
[RadioBackendContract](../app/src/main/java/fi/radioplus/app/RadioBackendContract.java)
and the [service interface](../app/src/main/aidl/com/hcn/autoradio/IRadioServiceAPI.aidl).

Experimental APKs also include support for selected older Junsun radios and
`com.ts.MainUI` versions. The experimental K4811 MCU profile uses
`com.nwd.radio.service` for tuning and `com.nwd.kernel` for audio-source routing;
both APKs are checked before control is enabled. These additions have not been
tested on real head units. NWD profiles are touch-control experiments without
Radio+ native steering-key integration, not equivalent to Junsun V7 support.
See [supported models and limitations](COMPATIBILITY.md).

## Tuning and audio playback

Selecting a station involves two separate operations:

1. Tune the receiver to the selected band and frequency.
2. Activate the radio audio source and request audio focus through the stock service.

Audio focus coordinates which app should be heard. The receiver can provide
station information and RDS text even when its audio is muted or another app is
playing. Receiving metadata alone therefore does not confirm audible playback.

When the user starts radio playback, Radio+ requests the radio audio route and,
when needed, renews the stock service's audio-focus request. It also attempts to
clear the tuner's mute state through the available manufacturer-specific
interface. These operations depend on the head unit's firmware; they are not a
universal Android tuner API.

Pausing live radio means **muting playback**. Radio+ does not record the broadcast
or resume from the point where it was paused. Its pause logic coordinates audio
focus and tuner mute rather than only changing the station's on-screen highlight.
The experimental TS and NWD adapters instead use their own inspected source-exit
commands; they do not apply HCN's focus or mute workarounds.

See [RadioPlaybackService](../app/src/main/java/fi/radioplus/app/RadioPlaybackService.java)
for playback control and
[OemFocusInteropPolicy](../app/src/main/java/fi/radioplus/app/OemFocusInteropPolicy.java)
for the audio-focus decision rules.

## Media controls

Next and previous use the list selected in Radio+:
**Stations** follows the saved station-list order, and **Favorites**
follows the favorites order. The selection survives app and service restarts.
Navigation wraps from the last entry to the first, or vice versa. An empty list
does nothing; it does not start a frequency search or borrow another list.

The screen arrows, Android media-session controls and supported steering-wheel
keys use the same navigation path. Holding a media key still selects one adjacent
station; tuner seeking remains in **Tuning**. Duplicate deliveries of the same
identified press are ignored. Radio+ confirms the frequency reported by the
stock service rather than displaying the requested station as if tuning succeeded.
Tuning an unnamed station shows its frequency without interrupting playback
with an editor. To name it, touch and hold its tile and choose **Rename**.

On a verified current HCN/Junsun V7, Radio+ remembers the station from before
a wheel press. If the stock radio also changes presets, Radio+ uses that starting
station to find the neighbour in the selected list. If the tuner is already on
that neighbour, it does not tune again. For consecutive keys it also accounts
for a stock preset reported just before the next Android DOWN: the last confirmed
wheel selection is the starting point unless another station is already established.
If the only conflicting evidence is an old poll, navigation follows the live
tuner just as it did in Community Beta 5.
An already active V7 radio route is not requested again on a wheel step,
screen arrow or station tile. Opening an already audible radio adopts its tuning and
audio unchanged. With Auto-start off and no remembered repair, the first deliberate
station selection can register Radio+ for media buttons.
A frequency change alone is not a button press. In experimental builds, the
**About app → Export support log** report distinguishes observed tuner changes
from received list commands while the ordinary radio screen is open.
In AGAMA 6.1.3's **Any active player** mode, its own virtual arrows step
through the selected list immediately; they do not wait for a physical key release.

Firmware must still deliver the key events to Radio+. The optional steering-key
accessibility service can route supported physical keys while Radio+ is visible;
it cannot handle commands that the firmware never exposes to Android.
On V7 units that deliver next/previous through Android media controls, no
accessibility service is needed. Enable the optional service only if the
buttons do not work on your head unit.

If the V7 steering test shows only tuner changes (`keys=0`), leave the radio
playing. Under **Settings → General → Steering-wheel buttons → Restore
steering-wheel controls**, Radio+ can ask Android to route media buttons to it
again. This does not enable accessibility, change presets, tune a station or
request audio focus. It uses one short local audio marker, then releases it.
No marker is played repeatedly in the background. A completed request is not
proof of delivery: test two separate presses against the displayed list while
parked. This cannot redirect keys handled entirely inside the head unit.

On the verified current V7, Auto-start also requests the wheel route after
startup or ACC wake, without waiting for a station tap. A completed manual
repair in Test Build 13 or later remembers this behavior even with Auto-start
off; it does not enable Auto-start or launch a stopped app. The automatic
request waits for the stock radio to settle, does not claim over active Android
audio or a known external source, and is cancelled if playback stops. It does
not repeat on every screen visit or station change.

Starting playback registers Radio+ for Android media controls. Changing stations
during playback reuses the active radio source to avoid an unnecessary audio
interruption. Opening the app is not treated as a Play command; playback or an
intentional pause should continue. Tuner hardware may still briefly mute while
changing frequency. On verified current HCN, resuming after Radio+'s own pause
restores tuner audio without replaying the delayed audio-routing pulse used
on cold starts. See [testing and known issues](TESTING.md).

## Station information

Radio+ reads the current frequency and RDS station name from the stock service.
Additional information, such as RDS RadioText, is read through manufacturer-specific
framework interfaces when available. The app refreshes the radio state in the
background and updates its interface when that state changes.

Station names and track information depend on the broadcast, reception and
firmware. Missing information cannot always be supplied by the app.

See [RadioServiceClient](../app/src/main/java/fi/radioplus/app/RadioServiceClient.java)
and [RadioMetadataReader](../app/src/main/java/fi/radioplus/app/RadioMetadataReader.java).

## What Radio+ provides

Radio+ implements its own interface, station and favorite management, custom logo
import, language selection and playback controls. The installed manufacturer
software remains responsible for access to the radio hardware and its audio path.

The public build does not bundle the stock radio APK, its service implementation,
tuner drivers or firmware. It ships without a station-logo collection; users can
[import their own permitted images](LOGOS.md).

## Compatibility and limitations

Confirmed working on the maintainer's **Junsun V7 running Android 13**. Other head
units may work if they expose compatible radio services and framework interfaces,
but compatibility is not guaranteed.

The same model name or Android version does not guarantee identical firmware
behavior. Audio routing, AM support, steering-wheel controls and sleep/wake
behavior can differ between devices.

Emulator tests cover app behavior and layouts, not real tuner reception or audible
radio playback. See [testing and known limitations](TESTING.md) for details, or
[report your head unit's compatibility](https://github.com/edman01/radio-plus-open/issues/new?template=compatibility.yml).

[Back to Radio+](../README.md)
