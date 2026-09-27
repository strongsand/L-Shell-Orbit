// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <Arduino.h>
#include "BeaconConfigStore.h"

enum class WifiConnectionState { IDLE, CONNECTING, CONNECTED, FAILED };

class WifiConnectionManager {
 public:
  void begin(const WifiCredentials& credentials);
  void disconnect();
  void update(uint32_t now);
  bool validateConnected(uint32_t now);
  WifiConnectionState state() const { return state_; }
  int32_t rssi() const;
  String localAddress() const;
  uint8_t failedAttempts() const { return failedAttempts_; }

 private:
  bool connectionValid() const;
  void logNetworkSnapshot() const;
  WifiCredentials credentials_;
  WifiConnectionState state_ = WifiConnectionState::IDLE;
  uint32_t startedAt_ = 0;
  uint32_t retryAt_ = 0;
  uint32_t lastStatusPollAt_ = 0;
  uint8_t failedAttempts_ = 0;
  int lastStatus_ = -1;
  bool eventLoggingRegistered_ = false;
};


