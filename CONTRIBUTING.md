# Contributing to Radio+

Please open a focused issue or pull request against this community repository.
Changes to original project code are contributed under the MIT license. Do not
submit third-party code or assets without the necessary rights and attribution.

## Report a bug

Include the release version, Android version, non-unique head-unit model/firmware
version, whether the stock radio works, reproduction steps, expected/actual
behavior and whether the issue occurs after sleep or switching audio sources.
For tuner problems distinguish UI changes/RDS updates from actual speaker audio.

Do not post serial numbers, VINs, device/account identifiers, email addresses,
home/work locations, passwords or complete device dumps. Redact screenshots and
log snippets. Do not upload the stock radio APK or firmware to this repository.

## Before a pull request

Run unit tests, lint and logo-isolation verification from the README. Add a
regression test for logic changes. For UI changes describe the screen size and
font scale tested. Do not claim that emulator-only tests verify analog FM audio,
CAN commands, physical steering buttons or ACC wake-up.

Keep signing material, local SDK paths, generated APKs and real user data out of
Git. Use neutral fixtures. Contributions should preserve user-selected names,
logos and favorite ordering and must not modify the manufacturer's firmware.
