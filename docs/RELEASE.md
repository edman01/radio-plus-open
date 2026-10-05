# Community beta 4

Free, open-source Radio+ for compatible Android head units.
Tested on **Junsun V7 running Android 13**.

## Download and install

- `RadioPlus-community-beta.apk`: signed APK without bundled station logos.
- `SHA256SUMS.txt`: checksum for the APK.
- Source archives: source code for this release.

Version: `0.16.1-beta22-play` · version code: `88`.
Package: `fi.radioplus.app.play`.

Requires **Android 8.1+** and a compatible stock **FMPlugService**.
Keep the original radio app installed and enabled. No firmware changes are needed.
Install and test only while parked.

## What's new

- Fixed next/previous steering-wheel controls on the tested Junsun V7.
  One press changes one station in the selected Stations or Favorites list.
- Fixed duplicate station changes and the delayed audio interruption after
  changing stations, including selection on the screen.
- Next/previous steering buttons do not need the optional accessibility service
  on the tested Junsun V7.
- Added **Settings → General → About app**, with the version and GitHub link.
- Added visible **Back** buttons to settings and its subdialogs.
- Improved playback continuity when returning to the app.

Steering-button and on-screen station changes were confirmed on the Junsun V7.
The app-return and settings-navigation changes have automated tests but still
need confirmation on the head unit.

## Features

- 12 languages: English, Finnish, German, French, Spanish, Portuguese, Italian,
  Swedish, Polish, Dutch, Turkish and Czech.
- Station lists, favorites and long-press editing.
- Custom PNG/JPEG logo import and removal.
- Scrollable dialogs and improved text wrapping.

## Known limitations

Other models and firmware variants are unverified. AM, steering-wheel mute and
sleep/wake behavior need further device testing.
[Testing and known issues](https://github.com/edman01/radio-plus-open/blob/main/docs/TESTING.md).

Station logos are not included. [Import your own logos](https://github.com/edman01/radio-plus-open/blob/main/docs/LOGOS.md).
[Installation guide](https://github.com/edman01/radio-plus-open/blob/main/docs/INSTALL.md) ·
[MIT license and third-party notices](https://github.com/edman01/radio-plus-open/blob/main/THIRD_PARTY_NOTICES.md).
