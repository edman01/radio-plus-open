# Install and update Radio+

Radio+ requires **Android 8.1 or newer** and a compatible stock radio service.
The community beta has been tested on **Junsun V7**. Dev36 is reported working
with firmware `MQ001_2026.09.18.19.50` and retains the earlier tested firmware.
Other models remain experimental; see [compatibility](COMPATIBILITY.md).

**Keep the original radio app installed and enabled.**
Radio+ uses the head unit's built-in tuner; it does not add a tuner to a phone or tablet.
Install and configure only while parked.

## Install

1. Download `RadioPlus-0.16.1-dev36.apk` from [community beta 5](https://github.com/edman01/radio-plus-open/releases/tag/v0.16.1-community-beta5).
   Its SHA-256 checksum is in `SHA256SUMS.txt` in the same release.
2. Copy the APK to the head unit, for example using a USB drive. Open it in
   Android's file manager and allow installation from that source.
3. Open **Radio+ → Tuning** to scan for stations, where supported, or enter a frequency manually.
   **Stations** shows the station list; **Favorites** shows your saved favorites.
4. Hold a station card to manage favorites, rename it, reorder it or
   [change its logo](LOGOS.md).

Choose your language in **Settings → General → Language**.
All 12 languages are included and work offline. English is the default.

Station names and RDS text depend on reception and the stock radio service.
You can rename a station if its name is missing.

## Steering-wheel buttons

Next/previous buttons work without an accessibility service on the tested Junsun V7.
For other compatible head units, **Steering-wheel buttons** offers an optional
fallback. Enable it only if the buttons do not already work. It cannot receive
commands that the firmware does not expose to Android.

## Update

Install the new community APK over the existing community app. Do not uninstall
or clear app data: that removes your saved stations, logos and settings.
Updates must use the same package and signing key.

The community package is `fi.radioplus.app.play`. It is separate from the stock
radio app; the `play` suffix is an existing package identifier.

Community beta 5 includes the [experimental profiles](EXPERIMENTAL.md) for
additional radio implementations; a separate experimental APK is not needed.
These profiles have not been verified on real head units and have feature limits.
Check the exact supported service/firmware before installing.

Beta 5 is version `0.16.1-dev36-play`, version code `98`. The older Experimental 3
download is dev31 / version code `93`; do not use it to downgrade beta 5.

## Problems?

Check [known issues](TESTING.md#known-issues) or [report a bug](../CONTRIBUTING.md).
Radio+ is free and ad-free. Do not post personal information or full device logs.

[Back to Radio+](../README.md)
