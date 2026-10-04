# Community beta 4

This is a free, MIT-licensed source release of Radio+'s original code, with a
signed installable Android beta. It is not a Google Play release or a manufacturer
approved app. Third-party rights are excluded as explained in the notices.

## Download

- `RadioPlus-community-beta.apk`: installable release APK, without station logos.
- `SHA256SUMS.txt`: SHA-256 checksum for that exact APK.
- GitHub's source archives: source code for this release.

Android version name: `0.16.1-beta22-play`, version code `88`.
Package: `fi.radioplus.app.play`. This installs beside the personal version and
does not replace the manufacturer's stock radio. The historical `play` suffix
does not imply distribution on Google Play.

**Requires Android 8.1+ and the compatible stock FMPlugService.** Keep the stock
radio installed/enabled. Generic Android phones and tablets are not supported
as real tuners. Test while parked at a low safe volume.

## New in this release

- Fixed next/previous steering-wheel controls on the tested **Junsun V7 running
  Android 13**. One press advances or goes back one station in the selected
  Stations or Favorites list.
- Fixed duplicate channel changes from one button press.
- Removed the brief audio interruption after an adjacent station change while
  radio playback is already active.
- On-screen station selection now also reuses active radio playback, avoiding
  an unnecessary audio handoff after selecting another station.
- Clarified that the optional steering-key accessibility service is not needed
  for next/previous buttons on the tested Junsun V7.
- Added **About app** under **Settings → General**, showing the installed version
  and a link to the GitHub project.
- Reopening the app now preserves ongoing radio playback or an intentional pause
  without starting another audio handoff.
- Added visible **Back** buttons to settings and its subdialogs. Returning from a
  subdialog preserves the selected settings category.

The steering-control correction and its audio behavior were confirmed on the
maintainer's physical Junsun V7, as was on-screen station selection in beta21.
The newer app-return and settings-navigation adjustments have automated coverage
but still need confirmation on the head unit.
Other models and firmware variants remain unverified.

## Included

- English default plus Finnish, German, French, Spanish, Portuguese, Italian,
  Swedish, Polish, Dutch, Turkish and Czech.
- Improved text wrapping and scrolling for smaller screens and larger font settings.
- Station/favorites lists and long-press editing.
- User-selected PNG/JPEG import and logo removal, without a bundled logo collection
  or background logo search.
- English installation/build instructions, privacy notes and test limitations.
- AI-assisted illustrations using fictional station names and original geometric logos.

## Limits

AM behavior, steering-wheel mute, sleep/wake and other firmware-specific behavior
still need separate device testing. The confirmed next/previous correction applies
to the tested Junsun V7 configuration. See
[testing](https://github.com/edman01/radio-plus-open/blob/main/docs/TESTING.md) before reporting an issue.

Station logos shown in the illustrations are not part of the APK.
Import your own permitted images using [the logo guide](https://github.com/edman01/radio-plus-open/blob/main/docs/LOGOS.md).
