# Changelog

## 0.2.0-beta.2 — prepared, not published

- Added PoE input voltage from compatible routers, with terminal association and freshness checks. This is not cable-output voltage or a current measurement.
- Added an optional Power Monitor for voltage, signed current/power and net energy received over Beacon HTTP, with explicit simulated-data labels, freshness and session charts.
- Expanded metric detail, retained history, reports and manual backup/restore, with an additive database upgrade from schema 6 to 8.
- Refined cards, editing, mini-charts, navigation, AMOLED, widgets and in-app test results.
- Improved manual/extreme/scheduled/smart tests, notifications, cancellation and result routing.
- Retained Beacon localization in Portuguese, English and Spanish; fixed widget colors and notification API compatibility.

Android controls scheduling and notification promotion; AR shows orbital predictions, not a confirmed radio link. Power Monitor displays received readings and never generates electrical samples locally. Beacon features depend on compatible firmware; Starlink monitoring does not require an electrical sensor.

## 0.2.0-beta.1

- Expanded local terminal telemetry, detailed metric views and retained history, with current readings kept separate from historical summaries.
- Added router monitoring, normal/extreme speed tests, custom scheduled times and optional smart tests during idle periods.
- Added daily, weekly and monthly reports, outage history, local report export and manual backup/restore.
- Refined navigation, charts, test notifications, home screen widgets and AMOLED mode.
- Expanded optional ESP32 Beacon collection/sync and introduced the experimental Android Beacon companion, with an API 21 compatibility path for older phones.
- Improved Portuguese, English and Spanish text, accessibility labels and small-screen layouts.

### Known limitations

- Equipment features depend on terminal/router firmware and LAN access; there is no verified hardware compatibility matrix.
- Android controls background timing and Live Update promotion. Scheduled times are best effort.
- AR requires public orbital data and shows estimates, not the terminal's connected satellites.
- Android Beacon needs a separate installation. API 21 and low-memory behavior still need real-device QA.
- The exact release APK still requires physical smoke testing. Beacon HTTP is for trusted LANs; exported backups are unencrypted.
