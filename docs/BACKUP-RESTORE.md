# Manual backup and merge restore

Backup and restore is separate from report JSON/CSV exports. The internal database
remains the app's source of truth. A report export cannot be restored as an app backup.

In **Settings → Data and export → Backup and restore**, create a ZIP using Android's
document picker. Keep it outside the app's private storage. Uninstalling the app
removes its internal data and recovery files, but not the document you saved.

## Format v1

`backupSchemaVersion: 1` is independent of the SQLite schema version. The ZIP
contains `manifest.json`, versioned logical JSONL collections and an allowlisted
`settings/preferences.json`. It never contains an open database, WAL, SHM or
SQLite file. Manifest fields include backup UUID, creation time, app version/code,
source database version, timezone, category counts, entry bytes, schema and SHA-256.

Collections include local/terminal/Beacon readings, events, compact observation
days, test results and available full execution details, extreme-test archives,
traffic session metadata and stored daily/weekly/monthly snapshots. Raw history
is preserved at its **currently retained resolution**; backup cannot recover data
already removed by retention. Provisional week/month views are dynamic, not saved
snapshots. No network requests are needed to create or restore a backup.

The validator reads every entry and record before offering a restore preview.
It rejects unsupported versions, malformed/inconsistent records, missing files,
wrong counts/hashes, duplicate paths, non-allowlisted paths, encryption, symlinks,
special files and unsafe compression/size/entry limits. V1 does not support ZIP64.
Limits: 256 MiB ZIP, 1 GiB decompressed, 2 MiB JSONL line, 2 million records per
collection, 48 nesting levels. Data is streamed through bounded private staging.
Prepared file merges additionally allow up to 10,000 file operations and 128 MiB
of payloads; traffic metadata retains the existing 1,000-session limit. A preview
that would exceed these bounds is refused without installing application data.
The saved SAF document is reopened and validated before creation is reported as
successful. SHA-256 detects corruption; **it is not encryption or authentication**.
Anyone with the ZIP can read or modify its contents and recompute the hashes.

## Preserved daily summaries

The daily-summary count is the number of compact summaries with actual readings,
metric/state/power coverage, events or tests, keyed by local date and timezone.
It is not installation age or a count of fully observed days. Zero-valued measured
metrics and aggregates retained after raw compaction are preserved.

Empty calendar placeholders are not persisted or exported. Earlier v1 backups may
contain them: their original manifest record counts and checksums are still validated,
then restore preview/merge skips them and displays the number ignored. An empty
local placeholder cannot block a meaningful imported summary. Existing summaries
with data remain unchanged. Calendar gaps still participate in weekly/monthly report
coverage denominators; snapshots, raw history and retention policies are unaffected.

## Restore rules

Select a backup, wait for complete validation, review counts and compatible
preferences, and confirm explicitly. Restore only merges missing records. It
preserves current data and existing snapshots with the same identity; it does
not erase the current installation. The preview is advisory: counts are checked
again inside the restore transaction because live collection can add records.

- Beacon identity: `(beacon_id, beacon_sequence)`.
- Terminal ring identity: `(source_boot, source_counter)` when present.
- Local readings/events without a persistent UUID: SHA-256 of canonical logical
  content, including timestamp, kind, origin, all metrics and explicit nulls.
  Identical observations at the same timestamp are considered the same record;
  numerical spellings/object ordering do not change that identity. Hash collision
  is theoretically possible; conflicting existing natural IDs always win.
- Tests: stable execution/result ID; original origin is preserved. Unknown future
  origins map conservatively to UNKNOWN for current analysis while retaining their
  original label. Extreme archives use their session ID.
- Traffic: UUID. Active metadata becomes interrupted history at backup time; no
  session/service/RPC resumes on restore.
- Reports: kind, stored period bounds and original zone when available. Existing
  closed snapshots win. Their JSON metrics, confidence, insights, distributions,
  algorithm versions and timestamps are restored rather than recalculated.

Older daily snapshots did not record a timezone/algorithm ID. Backup preserves
their stored bounds/format/creation time and does not invent those missing fields.
Restored daily snapshots are marked frozen to avoid the ordinary stale/backfill
regeneration path. Retention still applies to ordinary app operation after restore.
Historical normal tests previously stored only compact metrics and origin; their
unavailable old durations/details cannot be recreated. Available last execution
and extreme-round details are included. New terminal results retain their full
details in the existing result ledger; analytical calculations are unchanged.

## Transactions and interruption

SQLite records and a durable restore receipt commit in one transaction. A small
additive schema upgrade adds only `backup_restore_receipts`, without rewriting
history or changing source identities. A private journal prepares atomic file
merges and allowlisted preference writes before that transaction. If it fails,
SQLite rolls back and no files/preferences are installed. If the process dies
after the database commits, the next app/store initialization detects the receipt
and rolls forward the exact prepared file/preferences changes idempotently.
Recovery failure is visible and blocks normal startup until retry; it is never
reported as success. No receipt means the uncommitted prepared work is discarded.
Journal/staging cleanup follows successful recovery. The active journal is kept
in private durable storage, not disposable cache. Orphaned cache staging is cleaned.

Restore is unavailable during an active traffic recording or speed test. Short
local file writes and starts are serialized with restore; database writers rely
on SQLite transaction isolation. Beacon protocol, pagination and ACK logic remain
unchanged. Restored history does not restore Beacon pairing, credentials or cursors.
Configure the optional Beacon again normally on a clean installation.

## Preferences and privacy

Restore includes compatible theme/haptics/technical options, chart ordering,
alert cadence/silence, report preferences/retention, safe Beacon preferences and
export format. It excludes device permissions, bonds, keys/tokens, old SAF trees,
runtime jobs/notifications/active executions, monitor enablement, export automation
without a current folder, Beacon pairing/ACK/cursors and runtime budget ledgers.
Unknown, removed or invalid preferences are counted and skipped. Scheduled/smart
tests require a **separate explicit opt-in** in the preview; otherwise those
enable flags are skipped. Schedules are reconstructed using current app mechanisms,
not serialized WorkManager jobs. Re-enabling scheduled tests starts a fresh interval.

After installing compatible preferences, restore also reconfigures the existing
Beacon schedule so a restored sync interval takes effect. This preserves the
current background-sync opt-in and requires an already configured Beacon; backup
does not include pairing credentials or enable background sync on a new installation.

No broad storage permission, cloud SDK, tracking, upload, signing key or external
backend is involved. SAF providers may themselves be cloud-backed: the user chooses
the provider and destination. Backups contain historical timestamps, Beacon IDs
needed for deduplication, session names and alert text; treat them as private data.

## Physical validation checklist

Keep an independent copy of the original data/backup before uninstalling anything.
On a spare device or test profile: create with representative local+Beacon history,
all test origins, traffic sessions and closed daily/weekly/monthly reports; record
counts and selected metrics. Verify the saved document, then use a clean installation
without old permissions/pairing. Preview and restore; restart and compare history,
energy, graphs, reports, confidence/insights, tests, sessions and visual preferences.
Repeat the same restore and expect zero new records. Also test a partially populated
installation, corrupt ZIP, future schema, revoked SAF grant, full disk, cancellation,
process kill before/after DB commit and recovery retry. Confirm automation stays off
unless opted in and no old session/RPC/Beacon ACK is resumed. These SAF/device/lifecycle
scenarios **require physical validation**; local tests cannot certify them.
