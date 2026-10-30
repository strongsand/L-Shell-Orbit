# L-Shell Orbit

L-Shell Orbit is an open-source Android app for monitoring and diagnosing Starlink equipment on your local network. It needs no Starlink account or Google Play Services and has no analytics, advertising, or project cloud backend. Telemetry and history stay on your device. Optional AR downloads public orbital data from CelesTrak; speed tests use your Internet connection through the Starlink equipment.

> L-Shell Orbit is an unofficial community project and is not affiliated with, endorsed by, or sponsored by Starlink or SpaceX.

[Source code](https://github.com/strongsand/L-Shell-Orbit) · [Issue tracker](https://github.com/strongsand/L-Shell-Orbit/issues)

## Features

- Local terminal and router status through the equipment's gRPC interface
- PoE input voltage reported by compatible routers, shown only for a fresh reading associated with the current terminal
- Connection, latency, traffic, obstruction, alert, and power history
- Local reports, notifications, diagnostics, and supported equipment controls
- Normal and extreme speed tests, optional scheduled times and smart tests during idle periods
- Daily, weekly and monthly reports, outage history, local export and manual backup/restore
- Camera and Android sensor based AR view using public orbital data
- Home screen widgets, Material 3 dynamic color and a true-black AMOLED mode
- Operation on AOSP based devices without Google apps
- Optional L-Shell Beacon support for protected provisioning and local history retention while the phone is away
- Optional Power Monitor for electrical readings received over compatible Beacon HTTP, with explicit simulated-data labels and session charts

Feature availability depends on the terminal, router model, firmware, network topology, and fields exposed by the local interface.

## Requirements

- Android 8.0 (API 26) or newer
- A supported Starlink terminal reachable on the local network, normally at `192.168.100.1:9200`
- Android SDK Platform 37.0 (`platforms;android-37.0`) and JDK 17 to build Orbit
- Camera, location, and device sensors for the corresponding AR functions

The app does not require a Starlink account. Router features also require the supported local router endpoint to be reachable.

The maintainer has reported on-device testing with a Nothing Phone (1) and real Starlink equipment in earlier development builds. The terminal/router model and firmware have not been recorded as a verified compatibility matrix. Physical smoke testing of this exact beta remains pending. Router monitoring and router speed tests depend on a compatible router at `192.168.1.1:9000`; third-party routers and bypass mode may not expose these functions.

The candidate `0.2.0-beta.3` (code `7`) adds a black-and-white AMOLED option and
identified live PoP latency during normal tests. Router PoE input voltage on
compatible equipment remains available. See the [changelog](CHANGELOG.md) for
changes and known limitations, and [Android build notes](docs/ANDROID-BUILD.md)
for the SDK 37.0 configuration. This candidate is not yet published.
The separate Android Beacon Host and experimental firmware/relay are not included.
AR satellite positions are predictions, not identification of the active link.

## L-Shell Beacon

L-Shell Beacon is an optional, local-only ESP32 companion for retaining Dishy history while the phone is away. Beacon Protocol v1 and firmware `0.1.0` provide bonded and encrypted BLE provisioning with a bounded setup session, NVS Wi-Fi storage, LAN discovery through `_lshell-beacon._tcp`, local `get_history` collection, circular flash storage and incremental synchronization into the app's existing history database. The Android app uses only standard platform APIs for BLE and LAN access.

The firmware source is under [`firmware/lshell-beacon/`](firmware/lshell-beacon/), its hardware/setup notes are in the [Beacon firmware README](firmware/lshell-beacon/README.md), and the shared contract is [Beacon Protocol v1](docs/BEACON_PROTOCOL.md). History transport remains plaintext on a trusted local network. Authenticated management/ACK is capability-gated; the Android host adds private QR pairing. OTA remains deferred.

The flow is `Starlink local -> Beacon -> Beacon local storage -> L-Shell Orbit`. No cloud account, remote server, Google Play Services or proprietary nearby-device SDK is involved.

The name is inspired by McIlwain L-shells, a concept used to describe regions of Earth's magnetic field. This is an identity reference only and is unrelated to the Starlink protocol.

L-Shell Orbit does not access Starlink billing, plans, subscriptions, the account dashboard, OAuth, cookies, or private account APIs. Existing write operations are limited to local commands explicitly initiated by the user, such as supported stow, unstow, reboot, GPS-inhibit, update-check, and test operations.

## Privacy and networking

Terminal and router metrics remain on the device and are stored locally. L-Shell Orbit contains no advertising, analytics, crash reporting, or third-party tracking SDKs.

The Android manifest permits cleartext traffic because compatible Starlink equipment uses local plaintext gRPC and L-Shell Beacon Protocol v1 uses local HTTP. These connections stay on the user's local network; the app does not send Starlink account data or terminal telemetry to an L-Shell Orbit cloud service. The optional CelesTrak catalog continues to use HTTPS with Android's system trust store.

The AR satellite catalog makes an HTTPS request to [CelesTrak](https://celestrak.org/) when that feature is opened. Valid orbital data is cached for six hours, and stale cached data can be used when the service is unavailable. Dishy monitoring does not depend on CelesTrak.

See [PRIVACY.md](PRIVACY.md) for the data-flow and permission details.

## Build from source

Install Android SDK Platform 37 and JDK 17, then open a terminal in the
source root. The Gradle Wrapper selects the expected Gradle distribution. The project
includes `app`, `dishyCore` and `beaconProtocol`; the separate Android
Beacon Host is not part of its settings or output.

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

Protocol Buffer and gRPC bindings are generated once in `:dishyCore` during the normal Gradle build from `dishyCore/src/main/proto/device.proto`; generated bindings do not need to be committed. The schema's documented provenance and community references are described in [docs/DEVICE_PROTO_PROVENANCE.md](docs/DEVICE_PROTO_PROVENANCE.md).

## Screenshots

Five public phone screenshots are included under `fastlane/metadata/android/pt-BR/images/phoneScreenshots/`. The capture list and privacy checks are documented in [fastlane/metadata/android/SCREENSHOTS.md](fastlane/metadata/android/SCREENSHOTS.md).

The interface includes Brazilian Portuguese, English and Spanish resources, including notifications, widgets and accessibility labels. Technical messages supplied by equipment may retain their original wording.

## Known limitations

- The local Starlink gRPC interface is undocumented and can differ between hardware and firmware versions.
- Some metrics or controls may be unavailable when the equipment does not expose the corresponding field.
- Satellite positions are estimates derived from public orbital elements and are not the terminal's live connection list.
- The AR catalog requires CelesTrak data at least once before it can operate from cache.
- Scheduled tests are best effort: Android battery saving and background restrictions can delay or skip a time. Live Update promotion and notification appearance are controlled by Android and user settings.
- The separate Android Beacon app is experimental, declares Android 5.0 / API 21 support, and still needs physical validation on old, low-memory phones. API 16/19 and 512 MB RAM operation are not certified. The Orbit APK does not include or install this companion app or ESP32 firmware.
- Beacon history travels over plaintext HTTP on a trusted LAN. Supported hosts authenticate ACK/management, but history reads are not private from other LAN peers. Protect pairing codes and exported backups.

## Contributing

Before opening a change, keep the application functional without Google Play Services and avoid proprietary SDKs. Do not commit generated build output, signing material, credentials, or downloaded reference files.

## Third-party software and data

The local gRPC work used [sparky8512/starlink-grpc-tools](https://github.com/sparky8512/starlink-grpc-tools), released under The Unlicense, as a community reference. This attribution does not claim that its source code was copied into L-Shell Orbit.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Additional public documentation includes [Beacon Protocol v1](docs/BEACON_PROTOCOL.md), [device.proto provenance](docs/DEVICE_PROTO_PROVENANCE.md), [backup and restore](docs/BACKUP-RESTORE.md), and [widgets](docs/WIDGETS.md).

## License

Copyright (C) 2026 hurricane.

L-Shell Orbit is free software licensed under the **GNU General Public License v3.0 or later** (`GPL-3.0-or-later`). See [LICENSE](LICENSE).

SPDX license identifier: `GPL-3.0-or-later`.

Third-party libraries, data, references, and resources retain their own licenses and terms as documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The repository license does not imply ownership of the underlying Starlink protocol, names, or trademarks.
