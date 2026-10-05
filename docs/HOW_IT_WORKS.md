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

Development builds also include experimental support for selected older Junsun
radios and `com.ts.MainUI` versions. These have not been tested on real head units.
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

Firmware must still deliver the key events to Radio+. The optional steering-key
accessibility service can route supported physical keys while Radio+ is visible;
it cannot handle commands that the firmware never exposes to Android.
The tested Junsun V7 does not need this accessibility service: its next/previous
buttons work through Android media controls without it. Enable the optional
service only if the buttons do not work on your head unit.

Starting playback registers Radio+ for Android media controls. Changing stations
during playback reuses the active radio source to avoid an unnecessary audio
interruption. Opening the app is not treated as a Play command; playback or an
intentional pause should continue. Tuner hardware may still briefly mute while
changing frequency. See [testing and known issues](TESTING.md).

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
