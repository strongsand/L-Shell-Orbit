# L-Shell Orbit

L-Shell Orbit is an open-source Android application for locally monitoring and diagnosing compatible Starlink terminals and routers. It can also run the small set of supported local actions that the user explicitly starts. It is designed to work without a Starlink account, Google Play Services, analytics, advertising, or cloud telemetry.

> L-Shell Orbit is an independent, unofficial project and is not affiliated with or endorsed by Starlink or SpaceX.

[Source code](https://github.com/strongsand/L-Shell-Orbit) · [Issue tracker](https://github.com/strongsand/L-Shell-Orbit/issues)

## Features

- Local terminal and router status through the equipment's gRPC interface
- Connection, latency, traffic, obstruction, alert, and power history
- Local reports, notifications, diagnostics, and supported equipment controls
- Camera and Android sensor based AR view using public orbital data
- Home screen widgets and Material 3 dynamic color
- Operation on AOSP based devices without Google apps
- Optional L-Shell Beacon support for protected provisioning and local history retention while the phone is away

Feature availability depends on the terminal, router model, firmware, network topology, and fields exposed by the local interface.

## Requirements

- Android 8.0 (API 26) or newer
- A supported Starlink terminal reachable on the local network, normally at `192.168.100.1:9200`
- Android SDK 35 and JDK 17 to build the project
- Camera, location, and device sensors for the corresponding AR functions

The app does not require a Starlink account. Router features also require the supported local router endpoint to be reachable.

## L-Shell Beacon

L-Shell Beacon is an optional, local-only ESP32 companion for retaining Dishy history while the phone is away. Beacon Protocol v1 and firmware `0.1.0` provide bonded and encrypted BLE provisioning with a bounded setup session, NVS Wi-Fi storage, LAN discovery through `_lshell-beacon._tcp`, local `get_history` collection, circular flash storage and incremental synchronization into the app's existing history database. The Android app uses only standard platform APIs for BLE and LAN access.

The firmware source is under [`firmware/lshell-beacon/`](firmware/lshell-beacon/), its hardware/setup notes are in the [Beacon firmware README](firmware/lshell-beacon/README.md), and the shared contract is [Beacon Protocol v1](docs/BEACON_PROTOCOL.md). Cryptographically authenticated LAN pairing and OTA remain deferred; the current history transport is plaintext and restricted by design to the trusted local network.

The flow is `Starlink local -> Beacon -> Beacon local storage -> L-Shell Orbit`. No cloud account, remote server, Google Play Services or proprietary nearby-device SDK is involved.

The name is inspired by McIlwain L-shells, a concept used to describe regions of Earth's magnetic field. This is an identity reference only and is unrelated to the Starlink protocol.

L-Shell Orbit does not access Starlink billing, plans, subscriptions, the account dashboard, OAuth, cookies, or private account APIs. Existing write operations are limited to local commands explicitly initiated by the user, such as supported stow, unstow, reboot, GPS-inhibit, update-check, and test operations.

## Privacy and networking

Terminal and router metrics remain on the device and are stored locally. L-Shell Orbit contains no advertising, analytics, crash reporting, or third-party tracking SDKs.

The Android manifest permits cleartext traffic because compatible Starlink equipment uses local plaintext gRPC and L-Shell Beacon Protocol v1 uses local HTTP. These connections stay on the user's local network; the app does not send Starlink account data or terminal telemetry to an L-Shell Orbit cloud service. The optional CelesTrak catalog continues to use HTTPS with Android's system trust store.

The AR satellite catalog makes an HTTPS request to [CelesTrak](https://celestrak.org/) when that feature is opened. Valid orbital data is cached for six hours, and stale cached data can be used when the service is unavailable. Dishy monitoring does not depend on CelesTrak.

See [PRIVACY.md](PRIVACY.md) for the data-flow and permission details.

## Build from source

Install Android SDK 35 and JDK 17 or 21, then clone the repository and open a terminal in its root directory. The Gradle Wrapper downloads and selects the expected Gradle distribution.

On Linux or macOS:

```sh
./gradlew test
./gradlew assembleDebug
./gradlew assembleRelease
```

On Windows:

```bat
gradlew.bat test
gradlew.bat assembleDebug
gradlew.bat assembleRelease
```

Generated APKs are placed under `app/build/outputs/apk/`. The release variant is unsigned unless signing is configured privately by the builder.

Release signing is intentionally not configured in source control. Create and protect your own signing key, then configure release signing only in your private build environment. Do not commit keys, passwords, or local signing properties.

Protocol Buffer and gRPC bindings are generated during the normal Gradle build from `app/src/main/proto/device.proto`; generated bindings do not need to be committed. The schema's documented provenance and community references are described in [docs/DEVICE_PROTO_PROVENANCE.md](docs/DEVICE_PROTO_PROVENANCE.md).

## Screenshots

Seven public phone screenshots are included under `fastlane/metadata/android/pt-BR/images/phoneScreenshots/`. The capture list and privacy checks are documented in [fastlane/metadata/android/SCREENSHOTS.md](fastlane/metadata/android/SCREENSHOTS.md).

The current interface is primarily in Brazilian Portuguese. The L-Shell Beacon flow has partial English and Spanish localization; see the [localization audit](docs/LOCALIZATION-AUDIT.md).

## Known limitations

- The local Starlink gRPC interface is undocumented and can differ between hardware and firmware versions.
- Some metrics or controls may be unavailable when the equipment does not expose the corresponding field.
- Satellite positions are estimates derived from public orbital elements and are not the terminal's live connection list.
- The AR catalog requires CelesTrak data at least once before it can operate from cache.

## Contributing

Before opening a change, keep the application functional without Google Play Services and avoid proprietary SDKs. Do not commit generated build output, signing material, credentials, or downloaded reference files.

## Third-party software and data

The local gRPC work used [sparky8512/starlink-grpc-tools](https://github.com/sparky8512/starlink-grpc-tools), released under The Unlicense, as a community reference. This attribution does not claim that its source code was copied into L-Shell Orbit.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The current F-Droid readiness review is in [docs/F-DROID-AUDIT.md](docs/F-DROID-AUDIT.md).

Additional public documentation includes [Beacon Protocol v1](docs/BEACON_PROTOCOL.md), [device.proto provenance](docs/DEVICE_PROTO_PROVENANCE.md), and the [first release checklist](docs/RELEASE-CHECKLIST.md).

## License

Copyright (C) 2026 hurricane.

L-Shell Orbit is free software licensed under the **GNU General Public License v3.0 or later** (`GPL-3.0-or-later`). See [LICENSE](LICENSE).

SPDX license identifier: `GPL-3.0-or-later`.

Third-party libraries, data, references, and resources retain their own licenses and terms as documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The repository license does not imply ownership of the underlying Starlink protocol, names, or trademarks.
