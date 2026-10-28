# Beacon authentication and compatibility

## Security boundary

ACK is a privileged mutation: it can allow history reclamation. Discovery/history
reads on the LAN do not provide confidentiality. HMAC authenticates requests and
responses; it is not TLS encryption. Do not expose the HTTP server to the Internet
or weaken identity/capability validation. A reachable IP is not a paired identity.

Pairing uses a random 256-bit secret. QR shape is
`lshell-beacon://pair?v=1&id=<id>&token=<token>`; these are placeholders, never real
payloads for documentation. Keep strict parser validation. Orbit protects stored
credentials with Android Keystore; Host uses private backup-excluded storage and
protects sensitive views; ESP32 provisions over encrypted/bonded BLE. Never print
tokens or export them in backups, NSD announcements, logs or crash reports.

## Exact canonical messages

UTF-8 strings below use **literal LF separators** (`\n`), with **no final newline**.
HMAC-SHA256 is encoded as lowercase hexadecimal. Numeric fields use decimal;
`anchor` is lowercase `true` or `false`. Variable names below are placeholders.

```text
Challenge request: GET\n{action}\n{id}\n{clientNonce}
ACK request:       POST\nack\n{id}\n{nonce}\n{sequence}
ACK response:      ACCEPTED\nack\n{id}\n{nonce}\n{sequence}
Challenge response:
CHALLENGE\n{action}\n{clientNonce}\n{id}\n{nonce}\n{boot}\n{anchor}\n{fromAck}\n{through}\n{records}
```

Request header: `Authorization: Beacon-HMAC <lowercase-hex-mac>`.
The shared accepted-response function generalizes `ack`/`sequence` to action/
through for other management operations. Do not reuse the ACK proof for reset,
discard or time-anchor commands: inspect that operation's exact source contract.
Clients verify signed challenge/accepted responses and expected identity, not
merely HTTP 200. Preserve constant-time MAC verification where implemented.

## Nonce lifecycle and durable ordering

Challenge requests contain a 128-bit client nonce (32 lowercase hex characters).
The server generates a short-lived 128-bit nonce, bound to the authorized action
and identity. One pending challenge replaces the previous one.

- **Android Host:** elapsed-time validity 0..60,000 ms, matching pending nonce,
  positive sequence and valid proof; consume before durable ACK callback.
- **ESP32:** 30,000 ms deadline, action/identity proof, range validation and
  single consumption before committing ACK. Preserve rollover-safe time policy.

Do not unify these TTLs based on a guessed common constant. Failure after nonce
consumption needs a fresh challenge, never replay acceptance. Collector ACK state
must persist before its successful signed response. On Orbit, the imported page
must persist **before sending** ACK; retries re-ACK only a proven durable prefix.

## Compatibility matrix

| Orbit / collector | Current behavior |
| --- | --- |
| Updated Orbit + old ESP32 without authenticated ACK capability | Legacy ACK path remains for compatibility |
| Updated Orbit + ESP32 advertising authenticated ACK | Requires management capability, saved credential and valid signed exchange |
| Old Orbit + new secure firmware | Old unauthenticated ACK cannot acknowledge protected history |
| Updated Orbit + Android Host | Requires Android implementation, authenticated ACK and absolute-timestamp capabilities; no legacy downgrade |

Orbit pins Android expectations from the paired implementation/capabilities.
For ESP32, the current policy chooses legacy/authenticated mode from reported
capabilities; do not claim comprehensive capability downgrade pinning beyond what
the code does. Failure within an advertised authenticated path does not justify
retrying unauthenticated ACK. Any further hardening needs compatibility review.

## Recovery and reset

Time recovery is authorization-sensitive: protect nonce/boot checks and never ACK
unrecoverable time merely to progress a cursor. ESP32 factory reset clears logical
identity and pairing secrets; imported Orbit history stays. Re-pair after reset
instead of substituting old credentials. See the full [protocol](BEACON_PROTOCOL.md).

For protocol changes, review all three consumers (Orbit, Android Host, ESP32),
negative authentication/replay cases, lost responses, durable restart and old/new
capability combinations. Never remove security for API/RAM/APK savings.
