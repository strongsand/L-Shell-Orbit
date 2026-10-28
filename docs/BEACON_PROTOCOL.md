# L-Shell Beacon Protocol v1

Authenticated LAN ACK and management are described below. Reserved encrypted
LAN pairing and OTA are separate features. See [Beacon authentication and
compatibility](BEACON-SECURITY.md) for canonical proofs and compatibility limits.

Status: interoperable contract for Android provisioning, ESP32 collection and local history sync.  
Protocol version: `1`  
Firmware version: `0.1.0`

This document is the source of truth for communication between L-Shell Orbit and L-Shell Beacon. Protocol v1 includes protected BLE provisioning, local Dishy history collection, circular storage and incremental Android synchronization. Cryptographically authenticated LAN pairing and OTA remain outside this version; fields marked **reserved** must not be presented as implemented.

## Principles

Optional development extension (2026-10-24):
electrical simulation schema 1 adds the opt-in
`electrical_simulation_v1` capability and `/v1/status.electrical` envelope only in
the ESP32 simulator build. It never adds simulated values to v1 history or its
`power_watts` field. Default firmware capabilities/responses, public read policy,
record version, boot anchors and authenticated ACK are unchanged. Orbit consumes that envelope in its optional Power Monitor and identifies simulated
readings explicitly; it does not merge them into Starlink history.

- Local network only; no cloud service or Starlink account.
- A new setup requires an encrypted and bonded BLE link plus an unexpired authenticated setup session. A factory-unconfigured Beacon opens setup automatically; a configured Beacon requires an explicit long BOOT-button hold to reopen setup.
- Wi-Fi credentials are never exposed through readable characteristics and are erased from volatile staging memory after use.
- LAN endpoints expose local terminal telemetry and device health, but never Wi-Fi credentials, Starlink account data or application secrets.
- Future authenticated endpoints will be versioned without changing the v1 provisioning UUIDs.
- Unknown JSON fields must be ignored. Missing fields must never be replaced with invented values.

## Communication phases

### Phase A: BLE provisioning

BLE is enabled only when no valid Wi-Fi configuration exists, saved credentials repeatedly fail, GPIO0 is held for about five seconds, or configuration is reset. The advertised name is `L-Shell Beacon XXXX`, where `XXXX` is the final four non-sensitive hexadecimal characters of `beacon_id`.

The Android accessory flow is optional. Opening the app, returning to the
foreground or visiting the Beacon page does not start BLE discovery or request
BLE permissions. Only the explicit **Configure Beacon** action starts that flow.
Dismissal or denial leaves it closed until another explicit action. A configured
Beacon continues LAN discovery/synchronization without requiring BLE permissions.

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

Provisioning negotiates `ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK` in both key-distribution directions. LE encryption and identity keys remain available for bonding and reconnection. The BR/EDR link-key bit is excluded: the Beacon exposes no Classic Bluetooth provisioning service, and cross-transport key derivation (CTKD) can fail on Android when the peer uses a static-random LE identity. Diagnostics print `BLE_BOND_KEY_MASK=3` and `BLE_CLASSIC_KEY_DERIVATION=DISABLED`; these are configuration flags, not key material.

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

The firmware does not contact an external time service. In the normal case it stores a 64-bit Beacon uptime from the local ESP timer. During status and history sync the Android client converts same-boot uptime to wall time. Android accepts a positive epoch timestamp or reconstructs capture time from the page uptime only when the record and page have the same valid boot generation. Negative capture uptime is valid for recovered Dishy samples predating Beacon startup. If neither clock mapping is available, Android rejects the entire page before persistence/ACK/cursor advancement and reports that the capture time cannot be recovered. It never substitutes sync time for history time; sequences remain the synchronization identity. Such records remain pending, subject to the existing finite buffer capacity. Previously imported records with receipt-time fallback are not rewritten: the Android database/backup lacks their original boot/capture reference. If a trusted local integration sets a valid system clock in a future compatible build, epoch milliseconds can be stored with the timestamp-valid flag without changing record v1.

## Binary circular history

LittleFS uses the existing `spiffs` data partition from the framework's `huge_app.csv`; the partition table is unchanged. NVS is not used per sample. `BeaconHistoryRecordV1` is a packed 64-byte record containing:

- record magic, version and size;
- monotonic 64-bit Beacon sequence;
- epoch milliseconds or signed boot-relative capture milliseconds (negative values represent samples recovered from Dishy that predate the Beacon boot);
- 64-bit Dishy history counter;
- latency, packet loss, download, upload, signal/SNR and `powerIn`;
- connectivity, validity flags and a 16-bit boot generation;
- CRC-32.

Two alternating metadata files contain generation, head, count, next sequence, ACK, ACK time and the last Dishy counter. A collection batch keeps `/history.bin` open; it flushes records before advancing metadata every 16 samples and flushes/closes again at batch completion. ACK also has a CRC-protected durable NVS `history/ack_state` checkpoint containing the sequence and recovery counters, so it can succeed while filesystem writes are under pressure. Boot scans record CRCs and retains the newest contiguous suffix to recover valid writes after power loss. NVS contains a one-time `history/initialized` marker and a boot-generation counter written once per boot. The marker permits formatting an erased data partition on first use, but prevents an automatic reformat and silent sequence reset after a later mount failure. The boot generation prevents Android from interpreting uptime captured before a reboot as belonging to the current boot.

Logical circular capacity permits overwriting the oldest slot, but filesystem headroom can stop collection earlier. Below 64 KiB of free space, new writes pause while history reads and ACK remain available. Under pressure, once all retained records are acknowledged, reclaim removes the drained data file and clears its head/count while preserving monotonic next sequence, ACK and last Dishy counter. Reclaim targets 128 KiB of free space before collection resumes. It never deletes unacknowledged records to make room. ACK is therefore not a general delete operation, but it can trigger this pressure-recovery path.

With the stock `huge_app.csv` data partition (approximately 896 KiB), reserving 48 KiB for filesystem metadata leaves roughly 13,000–13,500 records. At the five-second stride this is approximately 18–19 hours; the exact `capacity` reported by the device is authoritative because LittleFS overhead can vary.

## History HTTP API

`GET /v1/history/info` returns `protocol_version`, `record_version`, `oldest_sequence`, `newest_sequence`, `record_count`, `capacity`, `recording`, `dish_reachable` and `acknowledged_sequence`.

`GET /v1/history?after=<sequence>&limit=<n>` returns records with a sequence greater than `after`, in ascending order. `limit` is clamped to 1–500. The response contains `record_version`, `beacon_uptime_ms`, `beacon_boot_id`, `from_sequence`, `to_sequence`, `next_sequence`, `has_more` and `records`; each record carries its own `boot_id`. A cursor older than the circular buffer begins at the oldest retained record, creating an observable sequence gap without inventing missing samples.

Firmware advertising `authenticated_ack_v1` accepts `POST /v1/history/ack` only with a one-use management challenge, `Authorization: Beacon-HMAC <proof>` and a form body containing `beacon_id`, `nonce` and decimal `sequence`. The canonical request proof is `POST\nack\n<beacon_id>\n<nonce>\n<sequence>` (literal LF separators, no trailing LF). The response contains `ok`, `through_sequence` and `response_mac`, signed over `ACCEPTED\nack\n<beacon_id>\n<nonce>\n<sequence>`. Sequence must be positive, no newer than the newest retained or already acknowledged sequence; malformed/future values are rejected before any ACK persistence or reclaim. The nonce is consumed before a valid ACK's storage operation, including if that operation fails. An unauthenticated or legacy JSON ACK cannot advance this firmware's checkpoint. Normally ACK only advances the durable NVS checkpoint. Under storage pressure, acknowledging the complete retained range permits reclaim of the drained data file; sequences are not reset.

Android validates `/v1/info`, reads history metadata, starts from the greater of its persisted preference cursor and the maximum `beacon_sequence` already in `dish_history.db`, downloads pages, validates monotonic sequence and finite metrics, and imports each page in one SQLite transaction. Only after that transaction succeeds does it send ACK and advance its cursor. The unique partial index on `(beacon_id, beacon_sequence)` makes retries idempotent. Imported rows use source `BEACON` and the same history table consumed by charts, timeline, reports, investigator and energy views.

The LAN API is plaintext HTTP on the trusted local network in protocol v1. It exposes telemetry but never Wi-Fi credentials, Starlink account data or secrets. History reads remain unauthenticated. The new firmware requires authenticated ACKs; an updated Android client uses legacy JSON ACKs only when the connected firmware lacks `authenticated_ack_v1`. The BLE-issued management credential and one-use challenge protect ACK integrity, but HTTP does not hide telemetry or encrypt the LAN session. An older app cannot ACK history on the new firmware; the history remains on the Beacon until a compatible app syncs it or finite storage pressure stops collection.

## Button and LED

GPIO0 is active-low with pull-up; GPIO2 drives the onboard LED. Short press has no provisioning role. Holding for about five seconds enters or restarts BLE setup. Holding for ten seconds starts a visible reset pattern; releasing after that pattern requests the complete factory reset below. The new lineage uses a new static-random BLE setup address, allowing fresh Android bonding without reusing erased keys. The hardware/eFuse MAC is unchanged. Button processing and LED patterns are non-blocking and based on `millis()`.

Both the ten-second physical reset and the authenticated app reset erase Wi-Fi, bonds, identity, history, sequence, ACK, clock anchors and management credentials. Normal reboot preserves these stores. Full flash erasure is not required.

LED state contract:

- `BOOTING`: slow pulse.
- `SETUP_BLE`: slow blink.
- `WAITING_WIFI_CONFIG`: short periodic blink.
- `WIFI_CONNECTING`: two flashes and pause.
- `READY`: short heartbeat every four seconds.
- `RECORDING`: on.
- `RESETTING`: rapid blink, including the reset-armed indication.
- `PAIRING`: approximately 2 Hz.
- `SYNCING`: 15–20 Hz.
- `DISHY_UNAVAILABLE`: two long flashes and pause.
- `STORAGE_WARNING`: three flashes and pause.
- `ERROR`: repeating long flash.

## Reserved phase-2 security

The authenticated LAN protocol will define a reviewed suite using X25519/ECDH or an equivalent modern primitive, HKDF, AEAD (AES-GCM or ChaCha20-Poly1305), per-session keys, monotonic counters/nonces, replay windows, device-bound pairing credentials and revocation. That encrypted LAN session design remains deferred. The limited plaintext local maintenance authorization below is a separate v1 capability; it does not implement AEAD or replace the history transport.

Endpoints for encrypted LAN pairing and per-client key revocation remain undefined until that design is complete. The maintenance endpoints below are explicitly authorized capabilities, not unauthenticated substitutes for that future session design. The history endpoints described above are the explicitly local, plaintext v1 exception.

## Compatibility rules

- `protocol_version` is the major version. Android must reject unsupported major versions.
- New optional JSON fields may be added within major version 1.
- Existing fields cannot change meaning or type within v1.
- Capabilities are authoritative; absence means unavailable.
- Android must preserve unknown fields if a future transport object needs round-tripping, and must ignore unknown state/capability values safely.

## Historical metric coverage and clock limits

Record v1 stores instantaneous download/upload in Mbps, latency in ms, loss in
percent, optional signal and power in watts. It does not contain cumulative RX/TX
byte counters, explicit sample duration, obstruction measurements or detailed
outage/alert events. Sampling stride is five seconds; collections normally run
every five minutes. Android estimates bounded support from this cadence rather
than filling the time between arbitrary retained points. Optional metrics remain
independent and SQL NULL does not become a measured zero.

Recovering earlier-boot backlog requires a trusted reference for that boot.
The capability extension below persists host-provided boot anchors. Dishy counters
are never used as a cross-boot clock. Legacy records without an anchor still fail
safely; no automatic timestamp repair or discard is performed.


## Local maintenance and persistent boot clock capabilities

The experimental firmware advertises `management_v1`, `factory_reset_v1`,
`boot_time_anchors_v1`, `discard_unrecoverable_v1` and `authenticated_ack_v1` alongside existing v1
capabilities. Protocol major and 64-byte record version remain 1. Old firmware
without these capabilities continues supporting ordinary history synchronization;
maintenance is unavailable explicitly. Updating firmware does not manufacture
anchors for boots which never received UTC.

### Authorization and challenges

In the bounded bonded/encrypted BLE setup window, the encrypted info characteristic
can return `management_token` (random 256-bit value). Its initial creation runs
before advertising, on loopTask with the BLE radio initialized. Only one setup
connection is accepted at a time. It is never advertised in
mDNS, returned over LAN, logged or included in backups. Android stores it encrypted
with a per-Beacon Android Keystore AES-GCM key. Credentials survive ordinary reboot
but are erased on factory reset. Existing installations must reopen physical setup
and authorize the new app; if necessary forget only the app's Beacon configuration,
then hold BOOT for five seconds and reconfigure. Phone history remains intact.

Every maintenance request requires `Authorization: Beacon-HMAC <request proof>`. The
firmware accepts it only from its IPv4 LAN subnet. The setup token is never
transmitted over HTTP. HMAC-SHA256 authenticates commands,
challenges and acceptance responses; telemetry remains unencrypted and normal
history reads are not protected against an active LAN intermediary. The ACK proof protects its fields, but `/v1/info` capability discovery is still plaintext. BLE Secure Connections currently uses Just
Works bonding, not a displayed MITM-resistant code. Do not expose port 80 outside
the local network. History reads and durable ACK retain the existing persist-before-ACK order.

`GET /v1/management/challenge?action=factory_reset|time_anchor|discard|ack&beacon_id=<id>&client_nonce=<32-lowercase-hex>`
requires authorization and returns `nonce`, `beacon_id`, `boot_id`, `time_anchor_available`,
`from_ack`, `through_sequence`, `record_count` and `response_mac`. One active random 128-bit nonce
is held in RAM for 30 seconds, scoped to the action and lineage. A newer challenge
invalidates the previous one. It is consumed before the operation, including on
validation/write failure; reboot also invalidates it. Another challenge is needed
after timeout, failure or concurrent use. No credentials appear in this response.

Command bodies use `application/x-www-form-urlencoded` and always include `nonce`
and `beacon_id`. Unsupported capability, changed identity, missing token or rejected
challenge must not be treated as success. Management responses are bounded JSON.

The request proof and `response_mac` are lowercase hex HMAC-SHA256 using the
decoded 32-byte setup token. Canonical messages use UTF-8, literal LF separators,
base-ten integers, lowercase `true`/`false`, no trailing LF, and blank lines for
absent command fields. Both implementations use this fixed field order:

```text
challenge request: GET, action, beacon_id, client_nonce
command request: POST, action, beacon_id, nonce, confirmation, boot_id, epoch_ms
challenge reply: CHALLENGE, action, client_nonce, beacon_id, nonce, boot_id,
                 time_anchor_available, from_ack, through_sequence, record_count
accepted reply: ACCEPTED, action, beacon_id, consumed_nonce, through_sequence
ACK request: POST, ack, beacon_id, nonce, sequence
```

Each comma denotes a field separated by LF (not a literal comma). The client
verifies the reply MAC and fresh client/server nonce before trusting the preview
or acceptance. An observed proof cannot authorize a different action or edited
body. A captured challenge request can invalidate another pending challenge but
does not authorize a destructive command. This extension protects maintenance
integrity; it does not encrypt the existing LAN history transport.

### Factory reset

`POST /v1/factory-reset` also requires `confirmation=FACTORY_RESET`.
The app requires typing RESET after an explicit data-loss warning. Firmware first
commits `maintenance/reset_pending`, returns `ok:true` with a signed acceptance, leaves a brief response
grace period, stops HTTP/mDNS/BLE and further collection, and drains any bounded RPC
result without storing it. No running RPC task is forcibly deleted.

With files and radios closed, reset formats the LittleFS data partition and checks
its root is empty; restores ESP Wi-Fi driver's persistent defaults; clears app NVS
`wifi`, `pairing`, `identity`, `history`, `clock`, and Bluedroid `bt_config.conf`.
Firmware partitions, eFuse/MAC and PHY/factory calibration are not erased.
The pending marker clears only after all steps succeed. Failure stays closed with
an error LED; the next boot resumes cleanup before any ordinary services start.

A fresh random Beacon ID starts a new lineage, sequence 1, ACK 0, boot 1 and empty
history. The static-random BLE setup address derives from that new identity and
remains stable across ordinary reboots. This prevents Android's old keys from
being reused for the freshly reset device. Another phone can provision it directly.
Android forgets local configuration/cursor/management keys only after acceptance
and cancels scheduled old-device sync. Historical Android rows are never deleted;
new `(beacon_id, sequence)` values cannot collide with pre-reset rows.

### Boot anchors

`POST /v1/time/anchor` includes `boot_id` and `epoch_ms` supplied by the trusted host.
Firmware samples signed 64-bit monotonic `esp_timer` uptime at reception and commits
`{boot_id, epoch_ms, uptime_ms, source:host}` in a CRC-protected NVS blob. The first
anchor of a boot is immutable. Epoch must be between 2020 and 2100; boot must match
the current boot; all timestamp arithmetic detects signed overflow.

Android establishes this reference before sync when the capability and locally
stored management credential exist. No cloud/NTP service is added. Host clock
accuracy and LAN transit delay limit absolute time accuracy. A boot never contacted
by a trusted host cannot acquire a past UTC reference after it has ended.

Up to 32 anchors are retained. When adding an anchor, one CRC-checked scan retains
anchors for boots still represented in the ring, including ACKed but not expired
records. Obsolete anchors are removed on that write. Capacity exhaustion rejects
the new anchor instead of evicting a reference still needed. Invalid clock state
fails closed. NVS updates survive normal reboot; factory reset removes all anchors.
If all 32 anchors still reference retained ring records, boot 33 cannot obtain an
anchor. Its uptime-only rows then block strict history sync. Do not evict a referenced
anchor without proving all relevant readers can recover those timestamps. A future
change should expand the clock blob to 64/128 entries with a versioned, power-safe
NVS migration; this round does not alter the blob or silently discard old anchors.

The 16-bit record boot ID no longer wraps. At 65535 boots per lineage, collection
fails with `BOOT_GENERATION_EXHAUSTED`; an explicit factory reset starts a new ID.
The unchanged 64-byte layout therefore never aliases a retained older boot.

History pages add `current_session` and `boot_anchors`. Resolvable rows expose their
real UTC `timestamp_ms` while retaining original signed `capture_uptime_ms` for
uptime-based records. Android can also resolve raw rows from the matching anchor:
`epoch_ms + (capture_uptime_ms - uptime_ms)`. Negative captured uptime is legitimate
for Dishy catch-up predating Beacon startup. Unknown prior boot remains an error.

`GET /v1/history/info` additionally exposes durable `next_sequence`; an empty ring
with a preserved next sequence/ACK is not a sequence reset.

### Explicit legacy discard

New Android requests `time_policy=strict` on history pages. Firmware stops a page
before its first unresolvable record, so a valid preceding prefix can be persisted
and ACKed normally. If the first record is unresolvable it returns HTTP 409 with
`code=historical_time_unrecoverable`. No automatic ACK/cursor advance follows.

For discard, the challenge previews only the unrecoverable *pending prefix*, up to
the first record with recoverable time. The UI presents its exact count and ending
sequence. On confirmation it obtains a fresh challenge and requires the preview to
match. `POST /v1/history/discard` includes `confirmation=DISCARD_UNRECOVERABLE`.
Firmware revalidates that prefix, commits its explicit NVS discard boundary and
then trims the ring prefix. Newer valid records, counters and next sequence remain.
Older already-ACKed ring positions preceding that prefix are eligible for cleanup;
their Android history remains intact. ACK alone never authorizes this discard.

With filesystem headroom the affected positions are overwritten with empty records;
under pressure, physical reclaim may be deferred, but the durable boundary prevents
those rows from being served or resurrected on reboot. An entirely discarded file
is removed. No temporary full-history copy is created on a full filesystem. Full
factory reset always formats and verifies the complete filesystem independently.

Only an accepted discard advances Android's cursor. It does not report an import or
update last-successful-sync time. Retry sync to reach the following valid records;
another later unrecoverable prefix requires another explicit confirmation.

### Extended status

Status adds `configured`, `collecting`, `backlog_count`,
`oldest_backlog_timestamp_known`, `oldest_backlog_timestamp_ms`, `boot_generation`,
`time_anchor_available`, `current_session` and `firmware_version`; existing storage,
Wi-Fi and history fields remain. Unknown oldest UTC is null, never guessed.

### Validation

The firmware policy compile test evaluates timestamp overflow, boot exhaustion,
challenge expiry/replay/purpose and eight reset interruption boundaries with the
ESP32 compiler. It does not replace flash/NVS fault injection on physical hardware.
Android tests cover cross-boot clocks, unknown/ambiguous anchors, explicit preview
validation, durable empty rings, preserved phone identity and DB-before-ACK order.
Reset during RPC, power cuts, fresh bonding, Android Keystore, LED and flash-pressure
discard/reclaim require physical validation before deployment.


## Android host adapter (phase 2)

The independent Android host uses the same v1 history/challenge/HMAC/ACK wire
format. `implementation=android`, `android_beacon_v1` and `absolute_timestamp_v1`
distinguish it; its persistent identity is UUID, sequence SQLite AUTOINCREMENT.
It advertises the existing NSD service on a dynamic LAN port and pairs through a
private on-screen QR, not BLE. Orbit validates identity and signed challenge
before storing its token in the existing encrypted credential store. No time
anchor/reset/discard endpoint is implemented for Android; physical Beacon paths
remain unchanged. The Android host application is distributed separately from Orbit.
