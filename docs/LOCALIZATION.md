# Translation review

Radio+ includes 12 languages. English is the default, and all language resources
are available offline. This review covers the five languages added in community
beta 3: Swedish, Polish, Dutch, Turkish and Czech.

## Scope and limits

Each new language was reviewed against the English source for meaning, grammar,
natural phrasing, consistent terminology and UI context. The review covered all
172 translatable resource keys, including 119 legacy messages: tuning, scanning,
favorites, logo import/removal, errors, notifications, widgets and accessibility
instructions. This was AI-assisted review, not independent native-speaker approval.
It cannot certify that every phrase is idiomatic to every reader or region.

Checks preserve the distinction between automatic scanning, seeking the next
station and entering a frequency manually; removing a favorite does not mean
deleting the station. LOCAL and DX retain their strong-signal/weak-signal meaning.
Safety, privacy and hardware limitations are not weakened in translation. User
station names and broadcast RDS text are not translated. Frequency ranges, tuning
steps, placeholders and intentional spaces in composed messages are preserved.

## Terminology and corrections

- Swedish: the tuning tab uses **Frekvensval**, distinct from **Inställningar**
  (settings); manual tuning uses **Manuell frekvensinställning**. Related labels
  and navigation instructions were updated together.
- Polish: **Strojenie ręczne** and **Lista stacji** distinguish tuning from the
  station list. The steering hint now describes forwarding button commands,
  rather than literally forwarding buttons.
- Dutch: **Handmatig afstemmen** and **Zenderlijst** are used consistently.
  The steering hint refers to forwarding button presses.
- Turkish: the steering-service explanation was rewritten to describe commands
  and the visible-app restriction more naturally. **Frekans ayarı** is distinct
  from **Ayarlar**. The expression **ayrı ayrı** is intentional Turkish wording,
  despite a generic repeated-word lint warning.
- Czech: **Ruční ladění**, **Seznam stanic** and **Oblíbené** keep the actions
  distinct. The background-playback explanation was made more natural, and the
  safety instruction explicitly refers to a parked vehicle.

Manufacturer manuals were used only as terminology references, not as a complete
translation source or proof of Radio+ compatibility:
[Swedish](https://www.volvocars.com/se/support/car/v40/14w20/article/6d5f14ea4071f15ec0a801e8007f1989/),
[Polish](https://www.volvocars.com/pl/support/car/v60/16w46/article/5f89cc41e21df1e1c0a801e8006aba6d/5add885c16f56fc8c0a801e8000d5b21/3833c33540573c74c0a801e800b4c58b/),
[Dutch](https://www.volvocars.com/nl/support/car/v40/17w17/article/3833c33540573c74c0a801e800b4c58b/),
[Turkish](https://ownersmanual.hyundai.com/ivi/STD_GEN5W/AVNT/EU/Turkish/activatingradio.html),
[Czech](https://www.volvocars.com/cz/support/car/v60/14w46/article/650b70b91ceabef7c0a801e800b22850/).

## Automated verification

The resource checker validates coverage and formatting placeholders in all 11
non-default locales. Emulator tests exercise the language picker, persisted
selection, activity recreation and translated views. They do not establish native
fluency or real radio hardware compatibility. See [test results](TESTING.md).

Native speakers can suggest corrections through an issue or pull request. Include
the language, current text, suggested wording and where it appears in the app.
