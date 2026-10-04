# Install and update Radio+

Radio+ is a hardware beta for compatible Android head units, not a universal radio
app. It requires **Android 8.1 / API 27 or newer** and the stock
`com.hcn.autoradio.service.FMPlugService`. Keep the stock radio installed and enabled.
The maintainer has confirmed operation on their physical **Junsun V7**. Other
testing has been emulator-only; other models are unverified. Android version or
a “Junsun V7” label alone does not guarantee compatibility with every firmware.
Ordinary phones/tablets do not gain an FM tuner from this app.

## Install

1. Download `RadioPlus-community-beta.apk` from [Releases](https://github.com/edman01/radio-plus-open/releases).
   Compare its SHA-256 with `SHA256SUMS.txt` from the same release.
2. Copy the APK to the head unit, for example using a USB drive. Open it in Android's
   file manager and allow installation from that source if you trust the release.
3. Open **Radio+ → Tuning** to choose automatic scanning or manual tuning.
   **Stations** shows the station list; **Favorites** shows your saved favorites.
4. Hold a station card to add/remove a favorite, rename, change/remove its logo
   or reorder the list. [Import a logo](LOGOS.md).

English is the default. Finnish is available under **Settings → General → Language**.
RDS station names and radio text appear when supplied by the stock service. You
can rename a station yourself if its name is missing. Configure only while parked.

## Update

Release package: `fi.radioplus.app.play`. The `play` suffix is a legacy identifier,
not a claim of Google Play availability. It installs alongside the stock radio and
the older personal `fi.radioplus.app` package; personal app data is not migrated.

Future community updates must use the same package and signing key. Install the
new community APK over the existing community app. Do not uninstall or clear app
data to update a compatible installation: that removes local stations, logos and settings.

## Before reporting a problem

Audio, muting, source switching, ACC wake-up, widgets and steering controls depend
on the head unit and its firmware. See [known limitations and testing](TESTING.md)
and [the bug-reporting guide](../CONTRIBUTING.md). Do not post personal data or
full device logs. This beta has no trial limit, payment screen, subscription or ads.

[Back to Radio+](../README.md)
