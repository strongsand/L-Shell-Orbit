// SPDX-License-Identifier: GPL-3.0-or-later
#include "LedController.h"

void LedController::begin() {
  pinMode(pin_, OUTPUT);
  write(false);
  stateSince_ = millis();
}

void LedController::setState(BeaconState state) {
  if (state_ == state) return;
  state_ = state;
  stateSince_ = millis();
}

void LedController::showResetArmed(bool armed) {
  if (resetArmed_ == armed) return;
  resetArmed_ = armed;
  stateSince_ = millis();
}

void LedController::write(bool on) { digitalWrite(pin_, on ? HIGH : LOW); }

bool LedController::patternValue(uint32_t elapsed) const {
  if (resetArmed_) return (elapsed % 160) < 80;
  switch (state_) {
    case BeaconState::BOOTING: {
      const uint32_t phase = elapsed % 2000;
      return phase < 900;
    }
    case BeaconState::SETUP_BLE: return (elapsed % 1200) < 250;
    case BeaconState::WAITING_WIFI_CONFIG: return (elapsed % 900) < 180;
    case BeaconState::WIFI_CONNECTING: {
      const uint32_t p = elapsed % 1800;
      return p < 120 || (p >= 260 && p < 380);
    }
    case BeaconState::PAIRING: return (elapsed % 500) < 250;
    case BeaconState::SYNCING: return (elapsed % 60) < 30;
    case BeaconState::DISHY_UNAVAILABLE: {
      const uint32_t p = elapsed % 3000;
      return p < 600 || (p >= 800 && p < 1400);
    }
    case BeaconState::STORAGE_WARNING: {
      const uint32_t p = elapsed % 2400;
      return p < 180 || (p >= 360 && p < 540) || (p >= 720 && p < 900);
    }
    case BeaconState::ERROR_STATE: return (elapsed % 2000) < 900;
    default: return false;
  }
}

void LedController::update(uint32_t now) { write(patternValue(now - stateSince_)); }


