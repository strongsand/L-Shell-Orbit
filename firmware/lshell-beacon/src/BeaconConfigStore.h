// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <Arduino.h>

struct WifiCredentials {
  String ssid;
  String password;
  bool valid() const { return ssid.length() >= 1 && ssid.length() <= 32 && password.length() <= 63; }
  void clear() { ssid = String(); password = String(); }
};

enum class WifiConfigState { PRESENT, MISSING, INVALID, STORAGE_ERROR };

class BeaconConfigStore {
 public:
  WifiConfigState loadWifiState(WifiCredentials& output);
  bool loadWifi(WifiCredentials& output);
  bool saveWifi(const WifiCredentials& value);
  bool hasWifi();
  bool clearConfiguration();
};


