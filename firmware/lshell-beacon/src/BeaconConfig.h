// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <Arduino.h>

namespace BeaconConfig {
constexpr uint8_t kButtonPin = 0;
constexpr uint8_t kLedPin = 2;
constexpr uint16_t kHttpPort = 80;
constexpr uint32_t kSetupAuthorizationMs = 120000;
constexpr uint32_t kBleSetupWindowMs = 120000;
constexpr uint32_t kWifiConnectTimeoutMs = 20000;
constexpr uint32_t kWifiRetryDelayMs = 15000;
constexpr uint8_t kWifiAttemptsBeforeSetup = 3;
constexpr uint32_t kLongPressSetupMs = 5000;
constexpr uint32_t kLongPressResetMs = 10000;
constexpr uint8_t kProtocolVersion = 1;

#ifdef LSHELL_BEACON_FIRMWARE_VERSION
constexpr char kFirmwareVersion[] = LSHELL_BEACON_FIRMWARE_VERSION;
#else
constexpr char kFirmwareVersion[] = "0.1.0";
#endif

constexpr char kHardware[] = "ESP32-D0WD-V3";
constexpr char kMdnsService[] = "lshell-beacon";
constexpr char kMdnsProtocol[] = "tcp";

constexpr char kBleServiceUuid[] = "7d2ea1d0-6f2b-4b5f-9e20-4c53484c0001";
constexpr char kBleDeviceInfoUuid[] = "7d2ea1d0-6f2b-4b5f-9e20-4c53484c0002";
constexpr char kBleSetupStateUuid[] = "7d2ea1d0-6f2b-4b5f-9e20-4c53484c0003";
constexpr char kBleWifiSsidUuid[] = "7d2ea1d0-6f2b-4b5f-9e20-4c53484c0004";
constexpr char kBleWifiPasswordUuid[] = "7d2ea1d0-6f2b-4b5f-9e20-4c53484c0005";
constexpr char kBleApplyUuid[] = "7d2ea1d0-6f2b-4b5f-9e20-4c53484c0006";
constexpr char kBleSetupResultUuid[] = "7d2ea1d0-6f2b-4b5f-9e20-4c53484c0007";
}
