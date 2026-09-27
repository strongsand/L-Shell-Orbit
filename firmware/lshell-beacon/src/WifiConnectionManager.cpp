// SPDX-License-Identifier: GPL-3.0-or-later
#include "WifiConnectionManager.h"

#include <WiFi.h>
#include "BeaconConfig.h"

namespace {
bool validAddress(const IPAddress& address) {
  return static_cast<uint32_t>(address) != 0;
}

void logBegin(const WifiCredentials& credentials) {
  Serial.print(F("WIFI_SSID_PRESENT="));
  Serial.println(credentials.ssid.length() > 0 ? 1 : 0);
  Serial.println(F("WIFI_BEGIN_CALL"));
  Serial.flush();
}

void logBeginResult(wl_status_t result) {
  Serial.print(F("WIFI_BEGIN_RETURN="));
  Serial.println(static_cast<int>(result));
  Serial.println(F("WIFI_DHCP_WAIT_START"));
}
}

void WifiConnectionManager::begin(const WifiCredentials& credentials) {
  credentials_ = credentials;
  failedAttempts_ = 0;
  lastStatus_ = -1;
  lastStatusPollAt_ = 0;
  Serial.println(F("WIFI_CONNECT_START"));
  Serial.flush();
  if (!eventLoggingRegistered_) {
    WiFi.onEvent([](arduino_event_id_t event, arduino_event_info_t info) {
      if (event == ARDUINO_EVENT_WIFI_STA_CONNECTED) {
        Serial.println(F("WIFI_DHCP_START"));
      } else if (event == ARDUINO_EVENT_WIFI_STA_GOT_IP) {
        Serial.print(F("WIFI_LOCAL_IP="));
        Serial.println(WiFi.localIP());
      } else if (event == ARDUINO_EVENT_WIFI_STA_DISCONNECTED) {
        Serial.print(F("WIFI_DISCONNECT_REASON="));
        Serial.println(static_cast<int>(info.wifi_sta_disconnected.reason));
      }
    });
    eventLoggingRegistered_ = true;
  }
  WiFi.persistent(false);
  Serial.println(F("WIFI_MODE_CALL"));
  Serial.flush();
  const bool modeStarted = WiFi.mode(WIFI_STA);
  Serial.print(F("WIFI_MODE_RETURN="));
  Serial.println(modeStarted ? 1 : 0);
  WiFi.setAutoReconnect(true);
  logBegin(credentials_);
  const wl_status_t beginStatus = WiFi.begin(credentials_.ssid.c_str(), credentials_.password.c_str());
  logBeginResult(beginStatus);
  state_ = WifiConnectionState::CONNECTING;
  startedAt_ = millis();
}

void WifiConnectionManager::disconnect() {
  Serial.println(F("WIFI_DISCONNECT_CALL erase=0 radio_off=1"));
  Serial.flush();
  const bool disconnected = WiFi.disconnect(true, false);
  Serial.print(F("WIFI_DISCONNECT_RETURN="));
  Serial.println(disconnected ? 1 : 0);
  state_ = WifiConnectionState::IDLE;
  credentials_.clear();
}

void WifiConnectionManager::update(uint32_t now) {
  const int currentStatus = static_cast<int>(WiFi.status());
  if (state_ != WifiConnectionState::IDLE &&
      (currentStatus != lastStatus_ || now - lastStatusPollAt_ >= 2000)) {
    Serial.print(F("WIFI_STATUS_POLL="));
    Serial.println(currentStatus);
    Serial.print(F("WIFI_LOCAL_IP="));
    Serial.println(WiFi.localIP());
    lastStatus_ = currentStatus;
    lastStatusPollAt_ = now;
  }
  if (connectionValid()) {
    if (state_ != WifiConnectionState::CONNECTED) {
      logNetworkSnapshot();
      Serial.println(F("WIFI_CONNECT_OK"));
    }
    state_ = WifiConnectionState::CONNECTED;
    failedAttempts_ = 0;
    credentials_.clear();
    return;
  }
  if (state_ == WifiConnectionState::CONNECTED) {
    logNetworkSnapshot();
    Serial.println(F("WIFI_CONNECT_FAIL"));
    Serial.println(F("WIFI_RETRY"));
    Serial.println(F("WIFI_RECONNECT_CALL"));
    const bool reconnectStarted = WiFi.reconnect();
    Serial.print(F("WIFI_RECONNECT_RETURN="));
    Serial.println(reconnectStarted ? 1 : 0);
    state_ = WifiConnectionState::CONNECTING;
    startedAt_ = now;
    return;
  }
  if (state_ == WifiConnectionState::CONNECTING && now - startedAt_ >= BeaconConfig::kWifiConnectTimeoutMs) {
    logNetworkSnapshot();
    Serial.println(F("WIFI_TIMEOUT"));
    Serial.println(F("WIFI_CONNECT_FAIL"));
    ++failedAttempts_;
    state_ = WifiConnectionState::FAILED;
    retryAt_ = now + BeaconConfig::kWifiRetryDelayMs;
    Serial.println(F("WIFI_DISCONNECT_CALL erase=0 radio_off=0"));
    Serial.flush();
    const bool disconnected = WiFi.disconnect(false, false);
    Serial.print(F("WIFI_DISCONNECT_RETURN="));
    Serial.println(disconnected ? 1 : 0);
  } else if (state_ == WifiConnectionState::FAILED && static_cast<int32_t>(now - retryAt_) >= 0) {
    Serial.println(F("WIFI_RETRY"));
    Serial.println(F("WIFI_CONNECT_START"));
    if (credentials_.valid()) {
      logBegin(credentials_);
      const wl_status_t beginStatus = WiFi.begin(credentials_.ssid.c_str(), credentials_.password.c_str());
      logBeginResult(beginStatus);
    } else {
      Serial.println(F("WIFI_RECONNECT_CALL"));
      const bool reconnectStarted = WiFi.reconnect();
      Serial.print(F("WIFI_RECONNECT_RETURN="));
      Serial.println(reconnectStarted ? 1 : 0);
    }
    state_ = WifiConnectionState::CONNECTING;
    startedAt_ = now;
  }
}

bool WifiConnectionManager::validateConnected(uint32_t now) {
  if (state_ == WifiConnectionState::CONNECTED && connectionValid()) return true;
  logNetworkSnapshot();
  Serial.println(F("WIFI_CONNECT_FAIL"));
  if (state_ == WifiConnectionState::CONNECTED) {
    Serial.println(F("WIFI_RETRY"));
    Serial.println(F("WIFI_RECONNECT_CALL"));
    const bool reconnectStarted = WiFi.reconnect();
    Serial.print(F("WIFI_RECONNECT_RETURN="));
    Serial.println(reconnectStarted ? 1 : 0);
    state_ = WifiConnectionState::CONNECTING;
    startedAt_ = now;
  }
  return false;
}

bool WifiConnectionManager::connectionValid() const {
  return WiFi.status() == WL_CONNECTED && validAddress(WiFi.localIP()) && WiFi.SSID().length() > 0;
}

void WifiConnectionManager::logNetworkSnapshot() const {
  const String associatedSsid = WiFi.SSID();
  Serial.print(F("WIFI_STATUS=")); Serial.println(static_cast<int>(WiFi.status()));
  Serial.print(F("WIFI_IP=")); Serial.println(WiFi.localIP());
  Serial.print(F("WIFI_GATEWAY=")); Serial.println(WiFi.gatewayIP());
  Serial.print(F("WIFI_RSSI=")); Serial.println(WiFi.status() == WL_CONNECTED ? WiFi.RSSI() : 0);
  Serial.print(F("WIFI_SSID_PRESENT=")); Serial.println(associatedSsid.length() > 0 ? 1 : 0);
}

int32_t WifiConnectionManager::rssi() const {
  return WiFi.status() == WL_CONNECTED ? WiFi.RSSI() : 0;
}

String WifiConnectionManager::localAddress() const {
  return WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString() : String();
}

