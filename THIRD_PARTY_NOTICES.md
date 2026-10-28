# Third-party notices

This file summarizes third-party components and data used by L-Shell Orbit. It is not a substitute for the license text distributed by each upstream project. Exact transitive versions should be regenerated from the release dependency graph whenever dependencies change.

The original L-Shell Orbit code and project-owned assets are distributed under `GPL-3.0-or-later`. The entries below retain their own licenses and terms.

## Runtime and build dependencies

| Component | Purpose | License | Source |
|---|---|---|---|
| AndroidX and Jetpack libraries, including Compose, Material 3, CameraX, Lifecycle, Navigation, WorkManager, DataStore and Glance | Android UI, lifecycle, camera, background work, preferences and widgets | Apache License 2.0 | https://github.com/androidx/androidx |
| Kotlin and kotlinx.coroutines | Language runtime and asynchronous work | Apache License 2.0 | https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines |
| gRPC Java and gRPC Kotlin | Local RPC transport and generated bindings | Apache License 2.0 | https://github.com/grpc/grpc-java and https://github.com/grpc/grpc-kotlin |
| Protocol Buffers | Message runtime and code generation | BSD 3-Clause | https://github.com/protocolbuffers/protobuf |
| Okio | I/O support used by resolved dependencies | Apache License 2.0 | https://github.com/square/okio |
| predict4java | Satellite orbit propagation | MIT License | https://github.com/davidmoten/predict4java |
| Guava and related Google Java utility artifacts | Java utilities used transitively | Apache License 2.0 | https://github.com/google/guava |
| Gson | JSON support used transitively | Apache License 2.0 | https://github.com/google/gson |
| Apache Commons Lang | Java utilities used transitively | Apache License 2.0 | https://commons.apache.org/proper/commons-lang/ |
| SLF4J API | Logging facade used transitively | MIT License | https://www.slf4j.org/ |
| JUnit 4 | Local unit tests only | Eclipse Public License 1.0 | https://github.com/junit-team/junit4 |
| `javax.annotation-api` | Compile-time annotations | CDDL 1.1 and GPL v2 with Classpath Exception | https://github.com/javaee/javax.annotation |

## L-Shell Beacon firmware toolchain

The project-owned sources under `firmware/lshell-beacon/` are licensed under `GPL-3.0-or-later`. They use these upstream open-source components when built; those components retain their own licenses:

| Component | Purpose | License | Source |
|---|---|---|---|
| Arduino core for ESP32 | Arduino runtime plus Wi-Fi, BLE, NVS/Preferences, mDNS, WebServer, RNG and mbedTLS integration | LGPL-2.1 | https://github.com/espressif/arduino-esp32 |
| PlatformIO Espressif 32 platform | Reproducible project/build integration | Apache-2.0 | https://github.com/platformio/platform-espressif32 |
| Espressif ESP-IDF components bundled by the selected Arduino core | Hardware, network and crypto support | Component-specific open-source licenses; upstream notices are authoritative | https://github.com/espressif/esp-idf |
| nghttp2 bundled with ESP-IDF | HTTP/2 transport for the minimal local Dishy gRPC request | MIT License | https://github.com/nghttp2/nghttp2 |

No third-party firmware source is copied into this repository. A future firmware release must preserve the notices and corresponding source obligations for the exact Arduino ESP32/ESP-IDF packages used to produce its binary.

Additional small annotation artifacts resolved transitively, including Checker Framework annotations, JetBrains annotations, JSpecify, Error Prone annotations, AutoValue annotations, J2ObjC annotations, Animal Sniffer annotations, PerfMark, `failureaccess`, and the empty `listenablefuture` compatibility artifact, are open-source support libraries. Their upstream license files remain authoritative.

## External data and certificates

- **CelesTrak orbital elements** are downloaded at runtime for the optional AR satellite view. CelesTrak's usage policy and data terms apply: https://celestrak.org/usage-policy.php
- **Jonathan McDowell** (https://planet4589.org) is credited for the Starlink identity/family facts used in `app/src/main/assets/starlink_generation_catalog.json`. Sources: https://planet4589.org/space/con/star/starindex.html and https://planet4589.org/space/con/star/stats.html. The constellation data page declares CC-BY without stating a version: https://planet4589.org/space/con/star/log.html. The transformed identity subset retains source links, review date and attribution; derivation and refresh tooling are documented in `docs/SATELLITE-GENERATION-CATALOG.md`. Generation coverage remains explicitly incomplete. Public CelesTrak OMM records in `app/src/test/resources/ar/` exercise parsing and identity resolution; CelesTrak data terms apply.

- **JSON-java 20240303** is used only by JVM tests to exercise Android-compatible JSON parsing; it is not added to the APK. Upstream declares Public Domain: https://github.com/stleary/JSON-java/blob/20240303/LICENSE.

L-Shell Orbit does not bundle a private or additional CA certificate. HTTPS connections, including CelesTrak, use the Android system trust store and normal hostname/certificate validation.

## References and project assets

- **Material Symbols Rounded** by Google, Apache License 2.0. Selected official
  24dp / weight-400 / grade-0 / fill-0 vectors replace the former generic icons
  in Orbit Compose UI, widgets and notifications. No font or runtime icon download
  is bundled. Source revision `10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7`:
  https://github.com/google/material-design-icons/tree/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7
  Local adaptations: Compose ImageVector conversion, Android tint/uniform scale
  and retained RTL mirroring. Semantic aliases and source checksums are recorded
  in `tools/material-symbols.json` and `tools/material-symbols-sources.json`.
  The unmodified upstream Apache license and local provenance notice are shipped
  in `app/src/main/assets/licenses/material-symbols-LICENSE.txt` and
  `material-symbols-NOTICE.txt`. There is no separate upstream NOTICE at this
  revision. Catalog: https://fonts.google.com/icons . This attribution does not
  imply Google endorsement. Project-owned brand/launcher vectors remain GPL.

- **starlink-grpc-tools** by `sparky8512` was used as a community reference for interacting with the observable local Starlink gRPC service. The project is released under The Unlicense: https://github.com/sparky8512/starlink-grpc-tools. This attribution does not assert that its source code was incorporated directly into L-Shell Orbit.
- L-Shell Orbit has used the MIT-licensed Dishylink project as an implementation reference. Dishylink code or branding must not be copied without retaining its copyright and license notice.
- The custom L-Shell Orbit icon supplied by the project owner remains distributed under `GPL-3.0-or-later` in the derived Android vectors `app/src/main/res/drawable/ic_dish_antenna.xml`, `app/src/main/res/drawable/ic_dish_antenna_launcher.xml` and `app/src/main/res/drawable/ic_dish_antenna_launcher_monochrome.xml`. The original editable SVG is no longer included in this source tree; its removal does not change the attribution or license of the distributed derivatives.

## Protocol interoperability definitions

`dishyCore/src/main/proto/device.proto` contains the minimum subset of definitions used by L-Shell Orbit. According to the maintainer-provided project history, it was synthesized during development from the observable local service interface and community open-source references, including `starlink-grpc-tools`; it was not downloaded as an official SpaceX schema. The current project copy and file header support the 2026-09-20 creation date, but the full original VCS history was not available for independent verification. See `docs/DEVICE_PROTO_PROVENANCE.md` for the factual record and remaining review item.


## Local QR pairing

- ZXing core (3.5.3 in the host; 3.4.1 through the scanner dependency in Orbit), Apache License 2.0, Copyright ZXing authors.
  https://github.com/zxing/zxing/blob/master/LICENSE
- ZXing Android Embedded 4.3.0 (com.journeyapps:zxing-android-embedded), Apache License 2.0.
  Copyright Journey Mobile, Inc. and contributors.
  https://github.com/journeyapps/zxing-android-embedded/blob/master/COPYING
  Used locally for the optional Android Beacon pairing camera; no external scanner
  application or Google Play Services is required.
