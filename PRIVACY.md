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

The manifest permits cleartext traffic for local-only protocols used by compatible equipment: plaintext gRPC to the Starlink terminal/router and HTTP for L-Shell Beacon Protocol v1. This permission is not used to transmit Starlink account information or local telemetry to a project cloud service. External CelesTrak catalog requests use HTTPS.

Opening the AR satellite feature can contact CelesTrak over HTTPS to obtain public orbital elements. As with any web request, the remote service and network intermediaries can observe ordinary connection metadata such as the public IP address and request headers. L-Shell Orbit does not append the user's location, equipment metrics, or a persistent app identifier to that request.

HTTPS certificate and hostname validation uses Android's standard system trust store. L-Shell Orbit does not install a CA, accept arbitrary certificates, or use a permissive `TrustManager`.

## Local storage

- Diagnostic samples, power samples, alerts, and related history are stored in the app's private local storage.
- Monitoring preferences and cursors are stored in app-private preferences.
- Beacon connection metadata and sync cursors are stored in app-private preferences. A Keystore alias field/helper is reserved for future authenticated LAN pairing; protocol v1 does not create a LAN pairing key or authenticate HTTP sync. Future secret key material must remain in Android Keystore, not preferences. BLE bonding/encryption is separate from LAN security.
- Downloaded orbital elements are stored in the app cache.
- Android backup is disabled for the application.

Uninstalling the app removes its app-private data according to normal Android behavior.

## Permissions

- **Internet**: communicates with the terminal/router on the local network and downloads the optional CelesTrak catalog.
- **Network state / network control prerequisite**: supports local-network monitoring and the connected-device foreground service on supported Android versions.
- **Wi-Fi state / multicast state**: allows optional mDNS/DNS-SD discovery of an L-Shell Beacon on the current local network, including multicast reception on older Android versions. It does not scan remote networks or upload discovery results.
- **Nearby devices / Bluetooth**: discovers and configures an optional L-Shell Beacon nearby. On Android 12 and newer this uses `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`; older Android versions may require location permission for BLE scanning. Discovery results remain local.
- **Camera**: displays the live preview in the AR feature.
- **Precise or approximate location**: obtains standard Android/GNSS location for sky-position calculations and calibration. Google Location Services is not used.
- **Notifications**: displays monitoring, alert, and report notifications where Android requires permission.
- **Foreground service**: keeps explicitly enabled local monitoring active and visible to the user.
- **Boot completed**: restores scheduled local report work after reboot or app update.

Permissions are requested only for features that need them. AR should remain unavailable or limited when camera/location permission or the necessary hardware is absent; local terminal monitoring does not require AR permissions.

L-Shell Beacon is optional. During provisioning, the Wi-Fi name and password travel directly from the phone to the nearby Beacon over an encrypted and bonded BLE link in a time-limited authenticated setup session. A factory-unconfigured Beacon opens setup automatically; a configured Beacon requires an explicit long BOOT-button hold to reopen setup. The password is write-only in the GATT protocol, is never returned by the firmware, and is stored in ESP32 NVS. The app does not persist the password in its database or preferences. The firmware collects Dishy history from `192.168.100.1:9200`, stores compact records locally and synchronizes them to the phone on the same LAN. Cryptographically authenticated LAN pairing remains deferred.

The Beacon flow adds no analytics, tracking, cloud synchronization, external time service, Google Play Services, Firebase or Starlink account access. Its plaintext LAN endpoints expose device/protocol identity, local terminal telemetry and implementation status to devices on the same network; they never expose Wi-Fi credentials or Starlink account data. Unsupported values are returned as `null` rather than invented.
