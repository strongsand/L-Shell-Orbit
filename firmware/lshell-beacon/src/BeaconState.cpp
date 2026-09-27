// SPDX-License-Identifier: GPL-3.0-or-later
#include "BeaconState.h"

const char* beaconStateName(BeaconState state) {
  switch (state) {
    case BeaconState::BOOTING: return "BOOTING";
    case BeaconState::SETUP_BLE: return "SETUP_BLE";
    case BeaconState::WAITING_WIFI_CONFIG: return "WAITING_WIFI_CONFIG";
    case BeaconState::WIFI_CONNECTING: return "WIFI_CONNECTING";
    case BeaconState::READY: return "READY";
    case BeaconState::RECORDING: return "RECORDING";
    case BeaconState::PAIRING: return "PAIRING";
    case BeaconState::SYNCING: return "SYNCING";
    case BeaconState::DISHY_UNAVAILABLE: return "DISHY_UNAVAILABLE";
    case BeaconState::STORAGE_WARNING: return "STORAGE_WARNING";
    default: return "ERROR";
  }
}


