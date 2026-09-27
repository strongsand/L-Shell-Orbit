// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <Arduino.h>

enum class ButtonEvent { NONE, SHORT_PRESS, ENTER_SETUP, RESET_CONFIRMED };

class ButtonController {
 public:
  explicit ButtonController(uint8_t pin) : pin_(pin) {}
  void begin();
  ButtonEvent update(uint32_t now);
  bool resetArmed() const { return resetArmed_; }

 private:
  uint8_t pin_;
  bool stablePressed_ = false;
  bool lastReading_ = false;
  bool resetArmed_ = false;
  uint32_t changedAt_ = 0;
  uint32_t pressedAt_ = 0;
};


