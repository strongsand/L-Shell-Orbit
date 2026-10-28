# Dishy core

Shared JVM client/schema for `:app` and `:beaconHost`. Existing package names and
Orbit normalization behavior are preserved. Source was moved from
`app/src/main/java/io/github/strongsand/lshell/core/` and `app/src/main/proto/`.
No Android UI, repository, service, persistence or Beacon firmware protocol lives
here. Protobuf Java Lite and gRPC Kotlin bindings are generated in this module.

`terminalHistoryValue` preserves each ring array's independent presence.
Consumers requiring NULL-versus-zero fidelity should use history arrays rather
than scalar status defaults.
