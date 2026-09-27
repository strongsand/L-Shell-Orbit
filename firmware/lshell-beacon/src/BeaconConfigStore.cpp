// SPDX-License-Identifier: GPL-3.0-or-later
#include "BeaconConfigStore.h"

#include <Preferences.h>

WifiConfigState BeaconConfigStore::loadWifiState(WifiCredentials& output) {
  output.clear();
  Serial.println(F("WIFI_NVS_SSID_READ_START"));
  Preferences prefs;
  if (!prefs.begin("wifi", true)) {
    Serial.println(F("WIFI_NVS_SSID_READ_RESULT=STORAGE_ERROR"));
    return WifiConfigState::STORAGE_ERROR;
  }
  const bool hasSsid = prefs.isKey("ssid");
  const bool hasPassword = prefs.isKey("password");
  output.ssid = prefs.getString("ssid", "");
  output.password = prefs.getString("password", "");
  prefs.end();
  Serial.print(F("WIFI_SSID_PRESENT="));
  Serial.println(hasSsid && output.ssid.length() > 0 ? 1 : 0);
  if (!hasSsid && !hasPassword) {
    Serial.println(F("WIFI_NVS_SSID_READ_RESULT=MISSING"));
    return WifiConfigState::MISSING;
  }
  if (!hasSsid || !hasPassword || !output.valid()) {
    Serial.println(F("WIFI_NVS_SSID_READ_RESULT=INVALID"));
    return WifiConfigState::INVALID;
  }
  Serial.println(F("WIFI_NVS_SSID_READ_RESULT=PRESENT"));
  return WifiConfigState::PRESENT;
}

bool BeaconConfigStore::loadWifi(WifiCredentials& output) {
  return loadWifiState(output) == WifiConfigState::PRESENT;
}

bool BeaconConfigStore::saveWifi(const WifiCredentials& value) {
  if (!value.valid()) return false;
  Preferences prefs;
  if (!prefs.begin("wifi", false)) return false;
  const bool ssidSaved = prefs.putString("ssid", value.ssid) == value.ssid.length();
  const bool passwordSaved = prefs.putString("password", value.password) == value.password.length();
  if (!ssidSaved || !passwordSaved) prefs.clear();
  prefs.end();
  return ssidSaved && passwordSaved;
}

bool BeaconConfigStore::hasWifi() {
  WifiCredentials value;
  const bool found = loadWifi(value);
  value.clear();
  return found;
}

bool BeaconConfigStore::clearConfiguration() {
  Preferences wifi;
  const bool wifiOpened = wifi.begin("wifi", false);
  const bool wifiCleared = wifiOpened && wifi.clear();
  if (wifiOpened) wifi.end();
  Preferences pairing;
  const bool pairingOpened = pairing.begin("pairing", false);
  const bool pairingCleared = pairingOpened && pairing.clear();
  if (pairingOpened) pairing.end();
  return wifiCleared && pairingCleared;
}

