# Languages and translations

Radio+ includes English (default), Finnish, German, French, Spanish, Portuguese,
Italian, Swedish, Polish, Dutch, Turkish and Czech. Choose a language in
**Settings → General → Language**. All languages are available offline.
User-supplied station names and broadcast RDS text are not translated.

## Suggest a correction

Translations may contain mistakes. Native speakers can suggest corrections
through an issue or pull request. Include the language, current text, suggested
wording and where it appears in the app.

## Translation contributions

Keep automatic scanning, seeking the next station and entering a frequency
manually distinct. Removing a favorite does not mean deleting the station.
LOCAL refers to strong-signal reception and DX to weak-signal reception.
Preserve safety instructions, privacy information, hardware limitations,
frequency ranges, formatting placeholders and intentional spaces in composed text.

Check resource coverage and formatting placeholders with:

```powershell
./tools/verify-translations.ps1
```

For layout changes, check the language picker, saved selection, activity
recreation and translated screens. See [testing](TESTING.md) for emulator
commands and hardware limitations.
