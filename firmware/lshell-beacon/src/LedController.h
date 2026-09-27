// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <Arduino.h>
#include "BeaconState.h"

class LedController {
 public:
  explicit LedController(uint8_t pin) : pin_(pin) {}
  void begin();
  void setState(BeaconState state);
  void showResetArmed(bool armed);
  void update(uint32_t now);

 private:
  void write(bool on);
  bool patternValue(uint32_t elapsed) const;
  uint8_t pin_;
  BeaconState state_ = BeaconState::BOOTING;
  uint32_t stateSince_ = 0;
  bool resetArmed_ = false;
};


