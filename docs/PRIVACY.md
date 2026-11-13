# Privacy

## Opt-in Reglink device-test log

Only the explicitly enabled Reglink test build keeps a bounded technical log in
app-private storage (two files, about 128 KiB total). It records the app version,
inspected radio-APK fingerprints, runtime tuner/source states, numeric tuning
commands, results and error categories. App crash records omit exception messages
and retain only limited Radio+ code locations. Suggested checks are guidance, not
proof of a diagnosis or audible reception.

No device identifiers, location, contact details, station names, typed text,
logos, document URIs or full Android logcat are collected by this logger. It does
not transmit anything. Export requires **About app → Export support log**, an
explanation/confirmation and a save location chosen through Android's document
picker. You decide whether and where to send that exported text file. Uninstalling
or clearing app data removes the private log; separately exported copies remain
where you saved them. This logging is disabled in normal builds.

Updated 13 November 2026. This describes the GitHub community build and the
separately enabled Reglink test logger above.

## Local data

Radio+ stores stations, favorites, imported logo copies, language and settings
in app-private storage. It reads tuner state and RDS information from the
installed stock radio service.

The app has no internet permission, accounts or server. It includes no analytics,
advertising, billing or remote crash-reporting SDK.

Android media controls, notifications and compatible local media clients
can display playback information and station lists. Diagnostic events may appear
in Android's local logcat. Radio+ does not automatically upload them.

## Optional steering-button service

The accessibility service is off unless you enable it in Android settings.
It handles supported next/previous/play/pause/mute keys while Radio+ is visible.
It does not request screen-content access or read typed text. You can disable it
in Android accessibility settings at any time.

## Imported images and deletion

You choose images through Android's document picker. A cloud storage provider
may download the chosen file according to its own policies. Radio+ keeps a local
copy, not the original document URI.

Use **Change logo → Remove logo** to delete the imported copy; the original file
is unaffected. Uninstalling or clearing app data removes saved stations, logos
and settings. Android backup is disabled for this app; manufacturer transfer
tools may behave differently.

## GitHub

GitHub hosts the repository, downloads and issue tracker under its
[privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
Issues and their attachments are public. Remove personal information, account
details, device identifiers and credentials before posting.

For security concerns, see [private reporting instructions](../SECURITY.md).
