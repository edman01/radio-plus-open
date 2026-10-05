# Install and update Radio+

Radio+ requires **Android 8.1 or newer** and a compatible stock radio service.
The community beta has been tested on **Junsun V7 / Android 13**.
Other models and firmware versions are not yet verified; see [compatibility](COMPATIBILITY.md).

**Keep the original radio app installed and enabled.**
Radio+ uses the head unit's built-in tuner; it does not add a tuner to a phone or tablet.
Install and configure only while parked.

## Install

1. Download `RadioPlus-community-beta.apk` from [community beta 4](https://github.com/edman01/radio-plus-open/releases/tag/v0.16.1-community-beta4).
   Its SHA-256 checksum is in `SHA256SUMS.txt` in the same release.
2. Copy the APK to the head unit, for example using a USB drive. Open it in
   Android's file manager and allow installation from that source.
3. Open **Radio+ → Tuning** to scan for stations or enter a frequency manually.
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

For additional radio models, an [experimental build](EXPERIMENTAL.md) is available.
Its radio support is unverified on real head units. It updates the same community
app and has a higher version code; read its installation notes before switching.

## Problems?

Check [known issues](TESTING.md#known-issues) or [report a bug](../CONTRIBUTING.md).
Radio+ is free and ad-free. Do not post personal information or full device logs.

[Back to Radio+](../README.md)
