// SPDX-License-Identifier: GPL-3.0-or-later
#include "ButtonController.h"
#include "BeaconConfig.h"

void ButtonController::begin() {
  pinMode(pin_, INPUT_PULLUP);
  lastReading_ = digitalRead(pin_) == LOW;
  stablePressed_ = lastReading_;
  changedAt_ = millis();
  if (stablePressed_) pressedAt_ = changedAt_;
}

ButtonEvent ButtonController::update(uint32_t now) {
  const bool reading = digitalRead(pin_) == LOW;
  if (reading != lastReading_) {
    lastReading_ = reading;
    changedAt_ = now;
  }
  if (now - changedAt_ < 35 || reading == stablePressed_) {
    if (stablePressed_ && now - pressedAt_ >= BeaconConfig::kLongPressResetMs) resetArmed_ = true;
    return ButtonEvent::NONE;
  }
  stablePressed_ = reading;
  if (stablePressed_) {
    pressedAt_ = now;
    resetArmed_ = false;
    return ButtonEvent::NONE;
  }
  const uint32_t held = now - pressedAt_;
  const bool confirmedReset = resetArmed_ && held >= BeaconConfig::kLongPressResetMs;
  resetArmed_ = false;
  if (confirmedReset) return ButtonEvent::RESET_CONFIRMED;
  if (held >= BeaconConfig::kLongPressSetupMs) return ButtonEvent::ENTER_SETUP;
  return ButtonEvent::SHORT_PRESS;
}


