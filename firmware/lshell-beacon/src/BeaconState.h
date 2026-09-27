// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

enum class BeaconState {
  BOOTING,
  SETUP_BLE,
  WAITING_WIFI_CONFIG,
  WIFI_CONNECTING,
  READY,
  RECORDING,
  PAIRING,
  SYNCING,
  DISHY_UNAVAILABLE,
  STORAGE_WARNING,
  ERROR_STATE
};

const char* beaconStateName(BeaconState state);


