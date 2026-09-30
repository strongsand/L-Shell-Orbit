# L-Shell Beacon firmware

Initial open-source firmware for an optional local collector used by L-Shell Orbit.

## Reference hardware

- classic ESP32 DevKit
- ESP32-D0WD-V3, dual core, 240 MHz
- 4 MB flash, no PSRAM
- GPIO0: BOOT/setup button, active low
- GPIO2: onboard status LED

The current firmware is version `0.1.0` and implements protected provisioning plus the first real local-history version of [Beacon Protocol v1](../../docs/BEACON_PROTOCOL.md).

## Implemented foundation

- persistent privacy-preserving Beacon identity in NVS;
- BLE advertisement and GATT provisioning service;
- BLE Secure Connections/bond reuse plus a bounded authenticated setup window;
- NVS Wi-Fi credential storage;
- Wi-Fi connection and bounded reconnect attempts;
- `_lshell-beacon._tcp` mDNS service and compact TXT records;
- public local status and versioned, paged history endpoints;
- non-blocking GPIO2 LED state machine;
- GPIO0 five-second BLE recovery/setup request and ten-second factory reset;
- a minimal HTTP/2 gRPC `get_history` collector for `192.168.100.1:9200`;
- 64-byte versioned records in a CRC-protected LittleFS circular buffer;
- incremental Android synchronization with durable sequence ACKs.

## Initial setup flow

1. Flash the firmware only after reviewing the board and partition settings for the target ESP32 DevKit.
2. On first boot, the Beacon advertises `L-Shell Beacon XXXX` over BLE because it has no saved Wi-Fi credentials.
3. In L-Shell Orbit, open **Functions → L-Shell Beacon → Set up Beacon**.
4. Select the nearby Beacon and accept Android's Bluetooth pairing request. The authenticated link opens a time-limited Wi-Fi setup session automatically.
5. Enter the local Wi-Fi SSID and password. The Beacon stores them in NVS, joins the network, announces `_lshell-beacon._tcp`, and then disables BLE.
6. Holding BOOT for about five seconds reopens or restarts BLE setup. Holding it for about ten seconds clears Wi-Fi and ESP32 bonds, then reboots with a distinct LED pattern.

The full UUID, state, timeout and endpoint contract is defined in [Beacon Protocol v1](../../docs/BEACON_PROTOCOL.md).

## Deliberately deferred

- cryptographically authenticated LAN pairing;
- remote/internet access;
- OTA firmware updates.

Protocol v1 history is plaintext HTTP and is intended only for the trusted local LAN. It exposes telemetry, never Wi-Fi credentials or Starlink account data.

## Building and validation

The source is arranged as a PlatformIO Arduino project. The PlatformIO Espressif32 platform is pinned to `7.1.3`; that release resolves the Arduino ESP32 core used by this source. `huge_app.csv` is retained: its existing data partition is mounted with LittleFS and no partition-table change was made. By 2026-09-30, the maintainer reported successful physical collection, Android sync, storage-pressure reclaim and resumed recording on the reference ESP32. This documentation review did not rerun builds or hardware tests. Changes still require targeted validation of BLE compatibility, nghttp2, memory use and flash recovery; see the collector, storage, recovery and provisioning contracts in [Beacon Protocol v1](../../docs/BEACON_PROTOCOL.md).

## Dependencies

Only components supplied by the open-source Arduino ESP32 core/Espressif SDK are referenced: Arduino, WiFi, Preferences/NVS, ESPmDNS, WebServer, LittleFS, nghttp2, the ESP32 BLE stack, ESP hardware RNG and mbedTLS SHA-256. The Arduino ESP32 core is LGPL-2.1; PlatformIO's Espressif32 platform is Apache-2.0; bundled ESP-IDF components retain their individual upstream licenses. See [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md). No proprietary SDK or cloud library is used.

## Privacy

Provisioning and normal operation are local. The firmware contains no SSID, password, account credential, analytics endpoint or cloud service. Wi-Fi credentials are accepted only in an authorized BLE setup session and are never logged or exposed by readable characteristics.

## License

Copyright (C) 2026 hurricane.

This firmware is part of L-Shell Orbit and is licensed under `GPL-3.0-or-later`; [LICENSE](LICENSE) contains the complete license text for standalone firmware source distributions. Libraries supplied by the Arduino ESP32 core retain their own licenses.
