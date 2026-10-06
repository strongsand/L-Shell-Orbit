# Screenshot capture list

Status reviewed 2026-10-06: seven stable pt-BR screenshots (`1.png`–`7.png`), identical to the assets in `v0.1.1-beta`, are also present in `en-US/images/phoneScreenshots/` as an untranslated listing fallback. The images were visually reviewed; no experimental UI or simulated translation was added.

For future replacements, capture real app screens after the release build is validated:

1. Home with the terminal connected and identifying values redacted where necessary.
2. Antenna metrics and charts.
3. Compact history/timeline with local and L-Shell Beacon sources.
4. Daily report and its graphs.
5. L-Shell Beacon status and synchronization, without local addresses or identifiers.
6. AR or installation/alignment view, without revealing precise location.

Use consistent light or dark theme captures and review every image before publication. Do not expose:

- personal SSIDs or Wi-Fi passwords;
- local IP addresses unless the screen genuinely needs them;
- home or installation addresses and precise location;
- private notification contents;
- personal names;
- MAC addresses;
- Beacon IDs or other persistent equipment identifiers;
- any other identifiable account, network or equipment data.

Place reviewed phone screenshots in the appropriate locale directory:

- `pt-BR/images/phoneScreenshots/`
- `en-US/images/phoneScreenshots/`

The same reviewed screenshots may be used for both store-listing languages for the first beta while most of the app UI remains in Portuguese. Replace the fallback copies when reviewed English captures become available.

The repository currently contains adaptive/vector launcher resources and the editable project-owned SVG at `app/src/main/res/raw/group_3_source.svg`. Android and F-Droid can obtain the installed icon from the APK resources. The 512×512 RGB PNG at `en-US/images/icon.png` is rendered from the official adaptive launcher foreground (`ic_dish_antenna_launcher.xml`) and background (`launcher_colors.xml`), preserving the existing paths, transforms and colors. No feature graphic is included.
