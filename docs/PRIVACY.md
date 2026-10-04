# Radio+ community beta privacy

Updated 4 October 2026. This describes the free GitHub community build.

## Data on the device

Radio+ stores station frequencies and names, favorites and their order, imported
logo copies, language and settings in app-private storage. It reads tuner state
and RDS information from the existing stock radio service. It has no INTERNET
permission and includes no analytics, advertising, billing or remote crash-report
SDK. There is no Radio+ account or Radio+ server in this build.

Android's media session, notification, widgets and media browser expose playback
information and station/favorite lists to compatible local system surfaces or
media clients. This is local integration, not a promise that other local apps
cannot access any station information. Diagnostic events can appear in Android's
local logcat; Radio+ does not automatically upload them.

## Optional steering key service

The accessibility service is off unless you enable it in Android settings. It
handles supported next/previous/play/pause/mute key events while Radio+ is visible.
It does not request screen-content access or read typed text. Disable it in
Android accessibility settings at any time. Firmware can prevent key delivery.

## Images and deletion

Android's document picker supplies the image you choose. A cloud provider may
download it according to that provider's policies. Radio+ imports a local copy
and does not retain the original document URI. Remove it through the station's
**Change logo → Remove logo** menu; the original image is unaffected. Clearing
app data or uninstalling removes local app data. Android backup is disabled in
the manifest; manufacturer migration tools may have separate behavior.

## GitHub and support

The repository, releases and issue tracker are hosted by GitHub. GitHub handles
account, access and download data under its own [privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
Public issue posts, attachments and contributions can be read by anyone. The
maintainers can read what you submit. Do not post names, email addresses, account
identifiers, location, vehicle identifiers, credentials or unredacted logs.

Use the repository's issue tracker for non-sensitive questions. See
[SECURITY.md](../SECURITY.md) for private vulnerability reporting where available.
