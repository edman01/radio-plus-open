# Radio+

An independent, open-source FM/AM radio interface for **compatible Android head units**.
This is a free **hardware beta**, not a universal Android radio app. It needs the
stock `com.hcn.autoradio` application and its `FMPlugService` to remain installed
and enabled. It does not replace the stock radio or change the firmware.

[Download the beta APK](https://github.com/edman01/radio-plus-open/releases)
· [Suomenkieliset ohjeet](README.fi.md)
· [Import your station logos](docs/LOGOS.md)
· [Known limitations](docs/TESTING.md)

## Preview

![Radio+ illustrative head-unit demo](docs/images/radio-plus-head-unit-demo.png)

Illustrative, AI-assisted composite based on an actual Android emulator capture.
The demo shows a private preview populated with station logos. **The downloadable
public APK contains no station logos and does not download them.** Users select
their own image files. Branding in the preview belongs to its respective owners;
the image is not evidence of hardware compatibility or manufacturer endorsement.
[Unedited emulator screenshot](docs/images/radio-plus-emulator.png).

## What it does

- Station list and a separate, ordered favorites list.
- Automatic scanning and manual FM/AM tuning, subject to the head unit's tuner API.
- RDS station names and radio text when supplied by the stock service.
- Long-press a station to add/remove a favorite, rename, change/remove its logo,
  or reorder stations/favorites. There is no corner-star delete shortcut.
- Android media-session controls and configurable station/favorites widgets.
- English by default, with Finnish available in Settings.

Actual radio audio, muting, source switching, ACC wake-up and steering controls
depend on the device firmware. Read the limitations before installing. Configure
the app only while parked; do not interact with settings while driving.

## Compatibility and installation

1. Check that the device runs **Android 8.1 / API 27 or newer** and has the compatible
   stock `com.hcn.autoradio.service.FMPlugService`. Android version or a “Junsun V7”
   product label alone does not prove compatibility. Ordinary phones/tablets do
   not gain an FM tuner from this app.
2. Download `RadioPlus-community-beta.apk` from this repository's Releases. Compare
   its SHA-256 with `SHA256SUMS.txt` from the same release.
3. Copy the APK to the head unit, for example using a USB drive. Open it in Android's
   file manager and allow installation from that source if you trust this release.
4. Keep the stock radio installed and enabled. Open **Radio+**, then **Tuning** to
   choose automatic scanning or manual tuning. **Stations** shows the station list.
5. Hold a station card to add it to **Favorites** or attach your own logo.

Release package: `fi.radioplus.app.play`. The `play` suffix is a legacy identifier,
not a claim of Google Play availability. This package installs alongside the stock
radio and the older personal `fi.radioplus.app` package. It does not migrate the
personal app's data. Future community updates must use the same package and signing
key. Do not uninstall or clear data to update a compatible installation.

This GitHub beta has **no trial limit, payment screen, subscription or ads**.

## Station logos

The app ships without a station-logo catalogue. Long-press the station →
**Change logo** → **Add custom logo from device…**, then select a PNG or JPEG from
USB storage or the device. To remove it, use **Change logo → Remove logo**.

160×120 images work; a larger original, up to approximately 512 pixels on the long
side, is useful for larger displays. Images keep their aspect ratio. Small images
are not enlarged by the importer, and large images are reduced to a maximum long
side of 512 pixels. A low-resolution source cannot gain detail by importing it.
See [the full workflow and troubleshooting](docs/LOGOS.md).

## Build from source

Required: **JDK 17**, **Gradle 8.11.1**, Android SDK **Platform 33** and Build Tools
**33.0.2**. This initial source release has no Gradle wrapper. Install Gradle or
configure that version in Android Studio. Set `ANDROID_HOME` to your SDK directory.
Dependency downloads require internet on the first build; the app itself has no
internet permission.

```sh
gradle :app:testPlayDebugUnitTest :app:lintPlayDebug :app:verifyPlayLogoIsolation
gradle :app:assemblePlayDebugAndroidTest
```

Debug APK: `app/build/outputs/apk/play/debug/app-play-debug.apk`.
Its package is `fi.radioplus.app.play.debug`, separate from the release app.

For UI preview without tuner hardware (debug build only):

```sh
adb install -r app/build/outputs/apk/play/debug/app-play-debug.apk
adb shell am start -n fi.radioplus.app.play.debug/fi.radioplus.app.MainActivity --ez preview true
```

Preview station names and radio text are fixtures, not live reception. Release
builds do not enable this preview mode. To run device tests, install the test APK
and invoke `androidx.test.runner.AndroidJUnitRunner`; see [testing](docs/TESTING.md).

For your own signed release, supply `RADIO_PLUS_KEYSTORE`,
`RADIO_PLUS_STORE_PASSWORD`, `RADIO_PLUS_KEY_ALIAS`, and `RADIO_PLUS_KEY_PASSWORD`
as environment variables, then run `gradle :app:assemblePlayRelease`.
Without signing variables the release is unsigned. Never commit private keys or
passwords. Your own signing key cannot update the project's signed APK in place.

## Privacy, license and attribution

[Privacy](docs/PRIVACY.md) · [MIT license](LICENSE) ·
[Third-party notices and rights limitations](THIRD_PARTY_NOTICES.md)

This is not an official Junsun, Škoda, Volkswagen Group or radio-station product.
No affiliation, approval or system-signing privilege is claimed. Manufacturer
services, station artwork and trademarks are not licensed by the MIT license.
The code license is not a legal clearance of third-party visual/design rights.

Please use [Issues](https://github.com/edman01/radio-plus-open/issues) for reproducible
bugs after reading [the reporting guide](CONTRIBUTING.md). Do not post personal
data, credentials, raw full-device logs or manufacturer APKs.
