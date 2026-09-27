# Provenance of `device.proto`

## Scope

`app/src/main/proto/device.proto` contains only the subset of Protocol Buffers definitions required by L-Shell Orbit to interoperate with the local gRPC service exposed by compatible Starlink equipment. It includes protocol names that must remain exact for wire compatibility, such as `SpaceX.API.Device`, `DishGetStatus`, and `DishGetHistory`.

The file is not presented as an official schema published or endorsed by SpaceX or Starlink.

## Observable facts in the current project copy

- The file defines a limited set of requests, responses, fields, and services used by L-Shell Orbit.
- Its earlier header records an initial version dated 2026-09-20 and a later `java_package` adjustment.
- The Android build generates Java and Kotlin bindings from this file with the standard open-source Protocol Buffers and gRPC toolchains.
- The current Java output package is `io.github.strongsand.lshell.proto`.
- Protocol identifiers required for interoperability remain in their observed technical form.

## Maintainer-provided history

The maintainer reports that the first version was created on 2026-09-20 as a minimal subset synthesized during development from the observable local service interface and community open-source references. The maintainer also reports that the later schema change was limited to `java_package` maintenance.

The original repository history was not present in the project copy used for this audit, so those historical details are recorded as **not independently verified** beyond what is consistent with the file's earlier header and current contents.

## Community reference

An important reference was [sparky8512/starlink-grpc-tools](https://github.com/sparky8512/starlink-grpc-tools), a community project for interacting with the local Starlink terminal gRPC service. That project is distributed under [The Unlicense](https://github.com/sparky8512/starlink-grpc-tools/blob/main/LICENSE).

This reference is identified as background for understanding the observable interface. The available project evidence does not establish that source code from `starlink-grpc-tools` was copied directly into L-Shell Orbit, so this documentation does not make that claim.

## Remaining review

Interface names and field layouts originate from an observed third-party device protocol. This document records the project's reconstruction history without claiming ownership of the underlying protocol, official publication by SpaceX, or a universal legal conclusion about interface definitions. Before an official source release, the maintainer should preserve any available original history and obtain legal advice if a definitive redistribution opinion is required.

The L-Shell Orbit project's original selection, arrangement, comments, and maintainable source contribution to this file are distributed with the project under `GPL-3.0-or-later`, to the extent those elements are licensable by the project maintainer. This statement does not claim ownership of the underlying protocol, technical identifiers, or third-party trademarks.
