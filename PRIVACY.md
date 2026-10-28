# Privacy

L-Shell Orbit is designed around local processing. It contains no advertising, analytics, behavioral tracking, cloud crash reporting, account login, or Starlink cloud API integration.

The Android application ID is `io.github.strongsand.lshell`. It identifies the installed application locally and is not used as a remote analytics or account identifier.

## Data flow

```text
Starlink terminal/router on the local network
  -> L-Shell Orbit
  -> local database and application preferences

CelesTrak public orbital catalog
  -> L-Shell Orbit AR feature
  -> temporary local cache

Optional L-Shell Beacon
  Phone -> protected local BLE setup -> Beacon
  Beacon -> local Wi-Fi/mDNS/HTTP status and history -> Phone
  Starlink terminal -> Beacon local circular storage -> L-Shell Orbit
```

L-Shell Orbit does not upload terminal metrics, router information, obstruction data, reports, location, or sensor readings to a L-Shell Orbit server or another analytics service.

Optional scheduled and smart speed tests are disabled by default. When enabled,
they request the existing speed test from the local Starlink router; the router
performs the test using the connection. The request disables optional test
telemetry reporting (`send_telemetry=false`), but test traffic itself necessarily
uses the connection. Smart tests inspect a bounded recent throughput history
locally, without uploading it. Preferences, limits and the latest result remain
in app-private storage. Automation uses Android WorkManager with battery/network
constraints and a visible notification while a test runs; it does not add
analytics, a project backend, continuous scanning or a permanent foreground service.

Connection health reports keep compact test results with their explicit origin for
up to 366 days, calendar summary caches for 400 days, and immutable weekly/monthly
aggregate snapshots in the same private local database. Local report generation
can be disabled in Reports. It does not start tests, collect additional telemetry
or send observations or insights to any server.

The manifest permits cleartext traffic for local-only protocols used by compatible equipment: plaintext gRPC to the Starlink terminal/router and HTTP for L-Shell Beacon Protocol v1. This permission is not used to transmit Starlink account information or local telemetry to a project cloud service. External CelesTrak catalog requests use HTTPS.

Opening the AR satellite feature can contact CelesTrak over HTTPS to obtain public orbital elements. As with any web request, the remote service and network intermediaries can observe ordinary connection metadata such as the public IP address and request headers. L-Shell Orbit does not append the user's location, equipment metrics, or a persistent app identifier to that request.

HTTPS certificate and hostname validation uses Android's standard system trust store. L-Shell Orbit does not install a CA, accept arbitrary certificates, or use a permissive `TrustManager`.

## Local storage

In the experimental next-release line, optional background Beacon sync uses
only the local network. Optional report export writes JSON/CSV and manifests
into an explicitly chosen document folder. The app does not request broad
storage access. Exported files contain diagnostic history and remain under the
user's control; the chosen document provider may synchronize them independently.
The internal database remains the app's primary data source.

Manual app backup is a separate, optional ZIP saved through Android's document
picker. It contains retained history, tests, reports, session names and compatible
preferences, including historical Beacon IDs needed for deduplication. It excludes
credentials, bonding keys, permissions, old document grants and runtime jobs.
Restoration validates the whole document and requires confirmation before merging.
Backups are readable JSON/JSONL, not encrypted; SHA-256 verifies integrity, not
confidentiality. Keep them private. No project server receives the backup; the
chosen document provider can synchronize it independently. A backup saved outside
app-private storage can survive uninstalling the app.

- Diagnostic samples, power samples, alerts, and related history are stored in the app's private local storage.
- Monitoring preferences and cursors are stored in app-private preferences.
- Beacon connection metadata and sync cursors are stored in app-private preferences. Management tokens are encrypted using AES-GCM with Android Keystore and the Beacon identity as authenticated data. Capable Beacons authenticate management/ACK using HMAC; history reads remain plaintext. BLE bonding/encryption is separate from LAN security.
- Downloaded orbital elements are stored in the app cache.
- Android backup is disabled for the application.

Uninstalling the app removes its app-private data according to normal Android behavior.

## Permissions

- **Internet**: communicates with the terminal/router on the local network and downloads the optional CelesTrak catalog.
- **Network state / network control prerequisite**: supports local-network monitoring and the connected-device foreground service on supported Android versions.
- **Wi-Fi state / multicast state**: allows optional mDNS/DNS-SD discovery of an L-Shell Beacon on the current local network, including multicast reception on older Android versions. It does not scan remote networks or upload discovery results.
- **Nearby devices / Bluetooth**: discovers and configures an optional L-Shell Beacon nearby. On Android 12 and newer this uses `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`; older Android versions may require location permission for BLE scanning. Discovery results remain local.
- **Camera**: displays the live preview in AR and scans an Android Beacon pairing QR only when explicitly opened.
- **Precise or approximate location**: obtains standard Android/GNSS location for sky-position calculations and calibration. Google Location Services is not used.
- **Notifications**: displays monitoring, test, alert, and report notifications where Android requires permission. Android 16+ promoted ongoing notifications use the non-runtime `POST_PROMOTED_NOTIFICATIONS` permission; user/system promotion settings still apply and an ordinary foreground notification remains the fallback.
- **Foreground service**: keeps explicitly enabled local monitoring active and visible to the user.
- **Boot completed**: restores optional scheduled local reports and automatic test work after reboot or app update.

Permissions are requested only for features that need them. AR should remain unavailable or limited when camera/location permission or the necessary hardware is absent; local terminal monitoring does not require AR permissions. Nearby-device permissions are requested from the explicit Beacon setup flow, not at startup. Android Beacon QR pairing separately needs camera access. Neither Beacon nor Bluetooth is required for ordinary Dishy monitoring.

L-Shell Beacon is optional. During provisioning, the Wi-Fi name and password travel directly from the phone to the nearby Beacon over an encrypted and bonded BLE link in a time-limited authenticated setup session. A factory-unconfigured Beacon opens setup automatically; a configured Beacon requires an explicit long BOOT-button hold to reopen setup. The password is write-only in the GATT protocol, is never returned by the firmware, and is stored in ESP32 NVS. The app does not persist the password in its database or preferences. The firmware collects Dishy history from `192.168.100.1:9200`, stores compact records locally and synchronizes them to the phone on the same LAN. History reads are plaintext and unauthenticated. ACK and maintenance protection depends on the advertised firmware capabilities, as described below.

The Beacon flow adds no analytics, tracking, cloud synchronization, external time service, Google Play Services, Firebase or Starlink account access. Its plaintext LAN endpoints expose device/protocol identity, local terminal telemetry and implementation status to devices on the same network; they never expose Wi-Fi credentials or Starlink account data. Unsupported values are returned as `null` rather than invented.


### Optional Beacon maintenance credentials

Complete Beacon reset and explicit unrecoverable-history discard use a random local
management credential obtained through the bonded/encrypted Bluetooth setup flow.
Android encrypts it with Android Keystore; it is excluded from logical backups and
never logged. Factory reset erases the Beacon credential and all Beacon-side user
history/configuration, while preserving history already imported on the phone.
Per-boot UTC anchors use the phone clock locally; no new cloud/NTP connection is
introduced. Maintenance HTTP is plaintext and intended only for a trusted LAN. Maintenance requests and
responses use nonce-bound HMAC-SHA256; the setup credential itself is never sent
over HTTP. History reads remain plaintext and unauthenticated. ACK authentication is
capability-gated: current ESP32 firmware advertising `authenticated_ack_v1` and the
Android host require nonce-bound HMAC; legacy firmware without that capability
retains its older ACK path. An authentication failure on a host advertising secure
ACK never falls back to the legacy path.


## Optional Android Beacon host

The separate L-Shell Beacon Android app collects local Dishy data into its own
private SQLite database. When enabled, it advertises a random logical identity
and generic Android type through local NSD and exposes history/status to devices
on the same trusted LAN, without TLS. ACKs require HMAC authentication; the random
256-bit management token appears only in an explicitly opened private pairing QR,
never in HTTP/TXT/logs. Orbit verifies identity/proof and stores it with its existing
AES-GCM Android Keystore credential store. The host retains its token in private,
backup-excluded app storage. No phone hardware identifier/account is used. Both
QR generation and scanning run on-device, without a cloud/Google Play service.
Unsynced host records are protected from age-based deletion; low space pauses
collection. Forgetting a Beacon in Orbit removes its local credential/configuration,
not imported history or the host data. Anyone who obtains the QR can authenticate;
keep it private. Details: docs/ANDROID-BEACON-HOST.md.
