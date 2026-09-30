# L-Shell Beacon Protocol v1

Status: interoperable contract for Android provisioning, ESP32 collection and local history sync.  
Protocol version: `1`  
Firmware version: `0.1.0`

This document is the source of truth for communication between L-Shell Orbit and L-Shell Beacon. Protocol v1 includes protected BLE provisioning, local Dishy history collection, circular storage and incremental Android synchronization. Cryptographically authenticated LAN pairing and OTA remain outside this version; fields marked **reserved** must not be presented as implemented.

## Principles

- Local network only; no cloud service or Starlink account.
- A new setup requires an encrypted and bonded BLE link plus an unexpired authenticated setup session. A factory-unconfigured Beacon opens setup automatically; a configured Beacon requires an explicit long BOOT-button hold to reopen setup.
- Wi-Fi credentials are never exposed through readable characteristics and are erased from volatile staging memory after use.
- LAN endpoints expose local terminal telemetry and device health, but never Wi-Fi credentials, Starlink account data or application secrets.
- Future authenticated endpoints will be versioned without changing the v1 provisioning UUIDs.
- Unknown JSON fields must be ignored. Missing fields must never be replaced with invented values.

## Communication phases

### Phase A: BLE provisioning

BLE is enabled only when no valid Wi-Fi configuration exists, saved credentials repeatedly fail, GPIO0 is held for about five seconds, or configuration is reset. The advertised name is `L-Shell Beacon XXXX`, where `XXXX` is the final four non-sensitive hexadecimal characters of `beacon_id`.

### Phase B: LAN discovery

After obtaining an address, the Beacon advertises DNS-SD service `_lshell-beacon._tcp` and exposes the public HTTP v1 endpoints. The default hostname is `lshell-beacon-xxxx.local`, preventing collisions when several Beacons share a LAN.

### Phase C: normal operation

Protocol v1 now includes local collection, circular history and incremental synchronization. Cryptographically authenticated LAN pairing remains a future extension; the current HTTP history API is intentionally limited to the local network and exposes no credentials or account data.

## Persistent identity

On the first boot, the firmware reads the hardware eFuse identifier, combines it with 128 random bits from the ESP32 hardware RNG, hashes that input with SHA-256, and stores the first 128 bits as lowercase hexadecimal in NVS key `identity/beacon_id`. Subsequent boots reuse the stored value. The full MAC address is not advertised or used as the public identifier.

Example short form: `a17c`; full example: `7c918cd55bb73c82f9010c4c78d4a17c`.

## BLE GATT contract

Service UUID:

`7d2ea1d0-6f2b-4b5f-9e20-4c53484c0001`

Stable characteristics:

| Characteristic | UUID | Properties | Value |
|---|---|---|---|
| Device info | `7d2ea1d0-6f2b-4b5f-9e20-4c53484c0002` | encrypted read | UTF-8 JSON |
| Setup state | `7d2ea1d0-6f2b-4b5f-9e20-4c53484c0003` | read, notify | state token |
| Wi-Fi SSID | `7d2ea1d0-6f2b-4b5f-9e20-4c53484c0004` | encrypted write | UTF-8, 1–32 bytes |
| Wi-Fi password | `7d2ea1d0-6f2b-4b5f-9e20-4c53484c0005` | encrypted write | UTF-8, 0–63 bytes |
| Apply/control | `7d2ea1d0-6f2b-4b5f-9e20-4c53484c0006` | encrypted write | command token |
| Setup result | `7d2ea1d0-6f2b-4b5f-9e20-4c53484c0007` | read, notify | UTF-8 JSON |

Device info:

```json
{"beacon_id":"…","firmware_version":"0.1.0","protocol_version":1,"hardware":"ESP32-D0WD-V3","bond_known":true}
```

Apply/control commands:

- `REQUEST_SETUP`: legacy-compatible request that refreshes the authenticated setup window; current Android clients do not need to send it.
- `APPLY`: validates staged credentials, saves them to NVS and attempts Wi-Fi connection.
- `CANCEL`: clears staged credentials and returns to `SETUP_BLE`.

Setup-state values:

- `SETUP_BLE`
- `WAITING_WIFI_CONFIG`
- `WIFI_CONNECTING`
- `COMPLETE`
- `ERROR`

Setup-result response:

```json
{"ok":true,"code":"OK","message":"configuration accepted"}
```

or:

```json
{"ok":false,"code":"WIFI_AUTH_FAILED","message":"Wi-Fi connection failed"}
```

The password characteristic is write-only. Reading it must be impossible. Writes to SSID, password and apply/control are rejected unless BLE authentication and bonding completed successfully. Credential writes additionally require an unexpired authenticated setup window.

### BLE security and setup authorization

The v1 firmware requires LE Secure Connections and bonding through the ESP32 BLE stack. This encrypts credential transport. The current `ESP_IO_CAP_NONE` configuration uses Just Works; it does not provide numeric-comparison/passkey MITM protection. Android discovers the public service before bonding so platform BLE stacks do not deadlock service discovery while encryption is settling:

1. Android opens GATT and discovers the public provisioning service.
2. It starts bonding only from `BOND_NONE`, waits during `BOND_BONDING`, and reuses `BOND_BONDED` without calling `createBond()` again.
3. After bonding and link authentication settle, Android enables notifications and reads device information.
4. Firmware reports `bond_known=true` only for the authenticated peer and opens `WAITING_WIFI_CONFIG` for 120 seconds.
5. Android may then write SSID/password and `APPLY`; every write is protected by encrypted GATT permissions and a firmware authenticated-session check.
6. Expiry, disconnect or `CANCEL` clears staged credentials and authorization.

The device information JSON includes `bond_known`. If Android reports `BOND_BONDED` while the Beacon no longer has the matching key, the app stops before sending protected setup commands and instructs the user to forget the Beacon in Android Bluetooth settings. It must not retry bonding in a loop. SSID, password and apply/control characteristics require an encrypted link at the GATT permission layer in addition to the firmware's authenticated-session check.

Expected diagnostic markers are `BOND_STATE_NONE`, `BOND_STATE_BONDING`, `BOND_STATE_BONDED`, `BOND_STATE_POLL=<n>`, `CREATE_BOND_CALLED`, `CREATE_BOND_SKIPPED_ALREADY_BONDED`, `GATT_CONNECTED`, `SERVICES_DISCOVERED` and `NOTIFICATIONS_READY` on Android. The bond-state broadcast is accepted from Android's privileged Bluetooth process, while a bounded poll of the live `BluetoothDevice.bondState` prevents setup from stalling when a vendor Bluetooth stack omits or delays that broadcast. Firmware markers are `BLE_CONNECTED`, `SECURITY_REQUEST`, `AUTH_COMPLETE_SUCCESS`, `AUTH_COMPLETE_FAILED`, `ALREADY_BONDED`, `PHYSICAL_CONFIRMATION_NOT_REQUIRED` and `WIFI_PROVISIONING_READY`. These markers contain no credentials or peer addresses.

For a configured Beacon, BLE setup is opened explicitly with a five-second BOOT hold and expires automatically. A factory-unconfigured Beacon may advertise until configured. BLE security in this phase does not replace the authenticated application protocol planned for LAN phase 2. No universal PIN or compiled key is permitted.

## Wi-Fi configuration

NVS namespace `wifi` contains `ssid` and `password`. Empty/missing SSID means unconfigured. Password is never logged or returned by BLE/HTTP. The firmware attempts reconnection with bounded backoff. After repeated failure it returns to setup BLE without deleting credentials automatically.

## mDNS/DNS-SD

- Service type: `_lshell-beacon._tcp`
- Instance: `L-Shell Beacon XXXX`
- Hostname: `lshell-beacon-xxxx.local`
- Port: `80`

TXT records:

| Key | Example | Meaning |
|---|---|---|
| `id` | `7c91…a17c` | persistent Beacon ID |
| `fw` | `0.1.0` | firmware version |
| `proto` | `1` | protocol major version |
| `paired` | `0` | LAN pairing status; always `0` until phase 2 |
| `setup` | `0` or `1` | BLE setup currently active |

TXT values should stay below 64 bytes and the complete announcement should remain small.

## Public LAN HTTP API

HTTP port 80 is used for the initial local implementation. Responses use `application/json; charset=utf-8`, integer protocol versions and lowercase `snake_case` fields. Unknown routes return `404` with an error object.

### `GET /v1/info`

Available before pairing:

```json
{
  "beacon_id":"7c918cd55bb73c82f9010c4c78d4a17c",
  "firmware_version":"0.1.0",
  "protocol_version":1,
  "hardware":"ESP32-D0WD-V3",
  "capabilities":["ble_provisioning","status","history_v1"]
}
```

### `GET /v1/status`

```json
{
  "state":"RECORDING",
  "uptime_seconds":421,
  "beacon_uptime_ms":421000,
  "boot_id":7,
  "wifi_connected":true,
  "wifi_rssi":-58,
  "paired":false,
  "recording":true,
  "dish_reachable":true,
  "last_collect_ms":null,
  "last_sample_ms":null,
  "last_collect_uptime_ms":420000,
  "last_sample_uptime_ms":419000,
  "last_sync_ms":null,
  "last_sync_uptime_ms":120000,
  "pending_records":60,
  "record_count":1320,
  "oldest_sequence":1,
  "newest_sequence":1320,
  "storage_used_bytes":84480,
  "storage_capacity_bytes":860160,
  "storage_healthy":true
}
```

`null` means that a measurement is not known yet. `recording` is true only after a successful history collection while Dishy remains reachable and storage remains healthy. SSID and Wi-Fi credentials are never returned.

## Official states

- `BOOTING`
- `SETUP_BLE`
- `WAITING_WIFI_CONFIG`
- `WIFI_CONNECTING`
- `READY`
- `RECORDING`
- `PAIRING`
- `SYNCING`
- `DISHY_UNAVAILABLE`
- `STORAGE_WARNING`
- `ERROR`

Android may use presentation-specific states such as scanning or found, but values received from the firmware use these exact tokens.

## Errors

| Code | Meaning |
|---|---|
| `OK` | operation accepted |
| `BLE_SECURITY_REQUIRED` | encrypted/bonded BLE session missing |
| `INVALID_COMMAND` | unknown control command |
| `INVALID_SSID` | SSID is empty or too long |
| `INVALID_PASSWORD` | password is too long |
| `WIFI_AUTH_FAILED` | association did not complete |
| `WIFI_TIMEOUT` | connection timed out |
| `MDNS_FAILED` | Wi-Fi connected, but the local discovery service could not start |
| `NVS_ERROR` | credentials/identity could not be stored |
| `INVALID_ACK` | history acknowledgement body is absent or malformed |
| `STORAGE_ERROR` | circular history could not be read or committed |
| `NO_MEMORY` | a bounded history page buffer could not be allocated |
| `NOT_IMPLEMENTED` | reserved v1 capability is unavailable |
| `INTERNAL_ERROR` | non-sensitive generic failure |

HTTP errors use `{"ok":false,"code":"…","message":"…"}` with an appropriate 4xx/5xx status.

## Dishy collection

The collector connects only to the local Dishy service at `192.168.100.1:9200`. It uses a small HTTP/2 gRPC client built on the ESP32 framework's bundled nghttp2 library and a purpose-built protobuf reader for `Device/Handle -> get_history`; it does not embed the Android protobuf runtime or a general gRPC framework. The request and response fields are restricted to the subset documented in this project.

- TCP reachability heartbeat: approximately 60 seconds.
- `get_history`: approximately five minutes, plus immediately after Dishy becomes reachable.
- Failed history calls retry after approximately 30 seconds.
- Dishy `current` and the real array length define the available ring range; no fixed retention duration is assumed.
- Dishy counter wrap/reboot is detected when `current` no longer exceeds the last saved counter.
- To limit flash wear and fit useful retention in the 4 MB ESP32, records are sampled from the 1 Hz Dishy buffer at a five-second stride, while always retaining the newest returned sample.
- `snr` is stored as signal when present. `power_in`, latency, loss and throughput remain optional per record.

The response is parsed incrementally during nghttp2 callbacks, without retaining a complete body. The logical message limit is 512 KiB; at most 2,048 floats per metric are retained. Six fully populated arrays require at most 48 KiB of float payload, in addition to parser/library overhead and a dedicated 20 KiB `dishGrpc` task stack. The task does not write storage: it hands its result to the main loop through a queue, keeping storage access serialized with HTTP handling. Arrays have independent lengths and absent optional metrics remain missing. Stack high-water and heap diagnostics are available for hardware validation.

The firmware does not contact an external time service. In the normal case it stores a 64-bit Beacon uptime from the local ESP timer. During status and history sync the Android client converts same-boot uptime to wall time. Records that predate the current Beacon boot receive the sync time as a conservative fallback because the reference clock was lost; sequence, rather than timestamp, remains the synchronization identity. If a trusted local integration sets a valid system clock in a future compatible build, epoch milliseconds can be stored with the timestamp-valid flag without changing record v1.

## Binary circular history

LittleFS uses the existing `spiffs` data partition from the framework's `huge_app.csv`; the partition table is unchanged. NVS is not used per sample. `BeaconHistoryRecordV1` is a packed 64-byte record containing:

- record magic, version and size;
- monotonic 64-bit Beacon sequence;
- epoch milliseconds or signed boot-relative capture milliseconds (negative values represent samples recovered from Dishy that predate the Beacon boot);
- 64-bit Dishy history counter;
- latency, packet loss, download, upload, signal/SNR and `powerIn`;
- connectivity, validity flags and a 16-bit boot generation;
- CRC-32.

Two alternating metadata files contain generation, head, count, next sequence, ACK, ACK time and the last Dishy counter. A record is flushed before its metadata is advanced; metadata is committed in batches of 16 records. ACK also has a CRC-protected durable NVS `history/ack_state` checkpoint containing the sequence and recovery counters, so it can succeed while filesystem writes are under pressure. Boot scans record CRCs and retains the newest contiguous suffix to recover valid writes after power loss. NVS contains a one-time `history/initialized` marker and a boot-generation counter written once per boot. The marker permits formatting an erased data partition on first use, but prevents an automatic reformat and silent sequence reset after a later mount failure. The boot generation prevents Android from interpreting uptime captured before a reboot as belonging to the current boot.

Logical circular capacity permits overwriting the oldest slot, but filesystem headroom can stop collection earlier. Below 64 KiB of free space, new writes pause while history reads and ACK remain available. Under pressure, once all retained records are acknowledged, reclaim removes the drained data file and clears its head/count while preserving monotonic next sequence, ACK and last Dishy counter. Reclaim targets 128 KiB of free space before collection resumes. It never deletes unacknowledged records to make room. ACK is therefore not a general delete operation, but it can trigger this pressure-recovery path.

With the stock `huge_app.csv` data partition (approximately 896 KiB), reserving 48 KiB for filesystem metadata leaves roughly 13,000–13,500 records. At the five-second stride this is approximately 18–19 hours; the exact `capacity` reported by the device is authoritative because LittleFS overhead can vary.

## History HTTP API

`GET /v1/history/info` returns `protocol_version`, `record_version`, `oldest_sequence`, `newest_sequence`, `record_count`, `capacity`, `recording`, `dish_reachable` and `acknowledged_sequence`.

`GET /v1/history?after=<sequence>&limit=<n>` returns records with a sequence greater than `after`, in ascending order. `limit` is clamped to 1–500. The response contains `record_version`, `beacon_uptime_ms`, `beacon_boot_id`, `from_sequence`, `to_sequence`, `next_sequence`, `has_more` and `records`; each record carries its own `boot_id`. A cursor older than the circular buffer begins at the oldest retained record, creating an observable sequence gap without inventing missing samples.

`POST /v1/history/ack` accepts `{"sequence":N}`. The sequence is capped at the newest stored record and persisted durably in NVS. Normally ACK only advances the checkpoint. Under storage pressure, acknowledging the complete retained range permits reclaim of the drained data file as described above; sequences are not reset.

Android validates `/v1/info`, reads history metadata, starts from the greater of its persisted preference cursor and the maximum `beacon_sequence` already in `dish_history.db`, downloads pages, validates monotonic sequence and finite metrics, and imports each page in one SQLite transaction. Only after that transaction succeeds does it send ACK and advance its cursor. The unique partial index on `(beacon_id, beacon_sequence)` makes retries idempotent. Imported rows use source `BEACON` and the same history table consumed by charts, timeline, reports, investigator and energy views.

The LAN API is plaintext HTTP on the trusted local network in protocol v1. It exposes telemetry but never Wi-Fi credentials, Starlink account data or secrets. Authenticated LAN sessions are explicitly deferred; clients must not interpret the present transport as end-to-end authenticated or add custom cryptography around it.

## Button and LED

GPIO0 is active-low with pull-up; GPIO2 drives the onboard LED. Short press has no provisioning role. Holding for about five seconds enters or restarts BLE setup. Holding for ten seconds starts a visible reset pattern; releasing after that pattern erases Wi-Fi and ESP32 Bluetooth bonds, then reboots. Android bonds must be forgotten from Android settings separately when performing a completely clean pairing test. Button processing and LED patterns are non-blocking and based on `millis()`.

The current button reset clears configuration/bonds, not the persistent Beacon identity or stored history. A full flash erase has different effects and must not be confused with this recovery action.

LED state contract:

- `BOOTING`: slow pulse.
- `SETUP_BLE`: slow blink.
- `WAITING_WIFI_CONFIG`: short periodic blink.
- `WIFI_CONNECTING`: two flashes and pause.
- `READY` / `RECORDING`: off.
- `PAIRING`: approximately 2 Hz.
- `SYNCING`: 15–20 Hz.
- `DISHY_UNAVAILABLE`: two long flashes and pause.
- `STORAGE_WARNING`: three flashes and pause.
- `ERROR`: repeating long flash.

## Reserved phase-2 security

The authenticated LAN protocol will define a reviewed suite using X25519/ECDH or an equivalent modern primitive, HKDF, AEAD (AES-GCM or ChaCha20-Poly1305), per-session keys, monotonic counters/nonces, replay windows, device-bound pairing credentials and revocation. No partial version is authorized by this document.

Endpoints for cryptographic LAN pairing, commands and key revocation remain undefined until that design is complete. Implementations must return `NOT_IMPLEMENTED` for those reserved capabilities rather than accept unauthenticated approximations. The history endpoints described above are the explicitly local, plaintext v1 exception.

## Compatibility rules

- `protocol_version` is the major version. Android must reject unsupported major versions.
- New optional JSON fields may be added within major version 1.
- Existing fields cannot change meaning or type within v1.
- Capabilities are authoritative; absence means unavailable.
- Android must preserve unknown fields if a future transport object needs round-tripping, and must ignore unknown state/capability values safely.
