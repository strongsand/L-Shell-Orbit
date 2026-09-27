# Project-specific rules. Library consumer rules are included automatically.

# Protobuf Lite builds message schemas reflectively from generated fields. Keep
# only this project's generated messages so R8 cannot rename or remove the
# members needed to parse Dishy and router gRPC responses at runtime.
-keep class io.github.strongsand.lshell.proto.** extends com.google.protobuf.GeneratedMessageLite { *; }
