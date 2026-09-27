// SPDX-License-Identifier: GPL-3.0-or-later
#include <Arduino.h>
#include <esp_system.h>

#include "BeaconConfig.h"
#include "BeaconConfigStore.h"
#include "BeaconHttpServer.h"
#include "BeaconIdentity.h"
#include "BeaconState.h"
#include "BeaconStorage.h"
#include "BleProvisioning.h"
#include "ButtonController.h"
#include "DishyCollector.h"
#include "LedController.h"
#include "MdnsService.h"
#include "WifiConnectionManager.h"

BeaconState beaconState = BeaconState::BOOTING;
BeaconIdentity identity;
BeaconConfigStore configStore;
BeaconStorage storage;
DishyCollector dishy(storage);
WifiConnectionManager wifi;
MdnsService mdns;
LedController led(BeaconConfig::kLedPin);
ButtonController button(BeaconConfig::kButtonPin);
BleProvisioning ble(configStore, identity);
BeaconHttpServer http(identity, wifi, storage, dishy, beaconState, led);

bool networkServicesActive = false;
bool mdnsHealthy = false;
uint32_t stopBleAt = 0;
uint32_t restartAt = 0;
const char* restartReason = nullptr;
bool clearBondsBeforeRestart = false;

const char* resetReasonName(esp_reset_reason_t reason) {
  switch (reason) {
    case ESP_RST_POWERON: return "POWERON";
    case ESP_RST_EXT: return "EXTERNAL";
    case ESP_RST_SW: return "SOFTWARE";
    case ESP_RST_PANIC: return "PANIC";
    case ESP_RST_INT_WDT: return "INTERRUPT_WATCHDOG";
    case ESP_RST_TASK_WDT: return "TASK_WATCHDOG";
    case ESP_RST_WDT: return "OTHER_WATCHDOG";
    case ESP_RST_DEEPSLEEP: return "DEEP_SLEEP";
    case ESP_RST_BROWNOUT: return "BROWNOUT";
    case ESP_RST_SDIO: return "SDIO";
    default: return "UNKNOWN";
  }
}

void setState(BeaconState next) {
  if (beaconState == next) return;
  beaconState = next;
  led.setState(next);
  Serial.print(F("STATE="));
  Serial.println(beaconStateName(next));
}

void enterSetup(bool keepOpenUntilConfigured = false) {
  Serial.println(F("ENTER_SETUP_BLE"));
  stopBleAt = 0;
  if (ble.active()) {
    Serial.println(F("BLE_RECOVERY_RESTART"));
    ble.stop();
  }
  ble.begin(keepOpenUntilConfigured);
  if (networkServicesActive) mdns.updateTxt(false, true);
  setState(BeaconState::SETUP_BLE);
}

bool startNetworkServices() {
  if (networkServicesActive) return mdnsHealthy;
  Serial.println(F("MDNS_START"));
  mdnsHealthy = mdns.begin(identity, ble.active());
  Serial.println(F("HTTP_START"));
  http.begin();
  networkServicesActive = true;
  return mdnsHealthy;
}

void stopNetworkServices() {
  if (!networkServicesActive) return;
  http.end();
  mdns.end();
  networkServicesActive = false;
  mdnsHealthy = false;
}

void connectSavedWifi() {
  WifiCredentials credentials;
  switch (configStore.loadWifiState(credentials)) {
    case WifiConfigState::PRESENT:
      Serial.println(F("CONFIG_PRESENT"));
      wifi.begin(credentials);
      credentials.clear();
      setState(BeaconState::WIFI_CONNECTING);
      return;
    case WifiConfigState::MISSING:
      Serial.println(F("CONFIG_MISSING"));
      break;
    case WifiConfigState::INVALID:
    case WifiConfigState::STORAGE_ERROR:
      Serial.println(F("CONFIG_INVALID"));
      break;
  }
  credentials.clear();
  enterSetup(true);
}

void setup() {
  Serial.begin(115200);
  Serial.println(F("BOOT"));
  const esp_reset_reason_t resetReason = esp_reset_reason();
  Serial.print(F("RESET_REASON="));
  Serial.println(static_cast<int>(resetReason));
  Serial.print(F("RESET_REASON_NAME="));
  Serial.println(resetReasonName(resetReason));
  Serial.println(F("STATE=BOOTING"));
  led.begin();
  button.begin();
  const bool storageReady = storage.begin();
  Serial.println(F("DISHY_COLLECTOR_INIT"));
  const bool dishyReady = dishy.begin();
  if (!storageReady || storage.pressure() || !dishyReady) {
    setState(BeaconState::STORAGE_WARNING);
  }
  if (!identity.begin()) {
    setState(BeaconState::ERROR_STATE);
    return;
  }
  connectSavedWifi();
}

void loop() {
  const uint32_t now = millis();
  led.showResetArmed(button.resetArmed());
  led.update(now);
  ble.update(now);
  wifi.update(now);

  if (ble.wifiProvisioningReady()) {
    setState(BeaconState::WAITING_WIFI_CONFIG);
  }
  if (ble.takeSessionExpired()) {
    Serial.println(F("BLE_SETUP_RETURN_TO_NORMAL"));
    if (wifi.state() == WifiConnectionState::CONNECTED) setState(BeaconState::READY);
    else connectSavedWifi();
  }

  switch (button.update(now)) {
    case ButtonEvent::SHORT_PRESS:
      break;
    case ButtonEvent::ENTER_SETUP:
      enterSetup(false);
      break;
    case ButtonEvent::RESET_CONFIRMED:
      stopNetworkServices();
      ble.stop();
      wifi.disconnect();
      if (configStore.clearConfiguration()) {
        setState(BeaconState::BOOTING);
        restartReason = "factory_reset";
        clearBondsBeforeRestart = true;
        restartAt = now + 1000;
      } else {
        setState(BeaconState::ERROR_STATE);
      }
      break;
    default:
      break;
  }

  WifiCredentials provisioned;
  if (ble.takePendingCredentials(provisioned)) {
    stopNetworkServices();
    wifi.begin(provisioned);
    provisioned.clear();
    setState(BeaconState::WIFI_CONNECTING);
  }

  if (wifi.state() == WifiConnectionState::CONNECTED && wifi.validateConnected(now)) {
    const bool networkReady = startNetworkServices();
    if (beaconState == BeaconState::WIFI_CONNECTING) {
      if (networkReady) {
        ble.reportWifiResult(true);
        mdns.updateTxt(false, false);
        setState(BeaconState::READY);
        stopBleAt = now + 2000;
      } else {
        ble.reportWifiResult(false, "MDNS_FAILED");
        setState(BeaconState::ERROR_STATE);
      }
    }
    if (!ble.active() && beaconState != BeaconState::WIFI_CONNECTING) {
      if (!storage.healthy() || storage.pressure()) setState(BeaconState::STORAGE_WARNING);
      else if (dishy.reachableKnown() && !dishy.reachable()) setState(BeaconState::DISHY_UNAVAILABLE);
      else if (dishy.recording()) setState(BeaconState::RECORDING);
      else setState(BeaconState::READY);
    }
    if (networkReady && !ble.active()) dishy.update(now);
  } else {
    stopBleAt = 0;
    if (networkServicesActive) stopNetworkServices();
    if (beaconState == BeaconState::READY || beaconState == BeaconState::RECORDING ||
        beaconState == BeaconState::DISHY_UNAVAILABLE) {
      setState(BeaconState::WIFI_CONNECTING);
    }
    if (wifi.state() == WifiConnectionState::FAILED &&
        wifi.failedAttempts() >= BeaconConfig::kWifiAttemptsBeforeSetup) {
      ble.reportWifiResult(false, "WIFI_TIMEOUT");
      wifi.disconnect();
      enterSetup(false);
    }
  }

  if (networkServicesActive) http.update();
  if (stopBleAt != 0 && static_cast<int32_t>(now - stopBleAt) >= 0) {
    Serial.println(F("BLE_STOP_AFTER_PROVISIONING"));
    ble.stop();
    stopBleAt = 0;
  }
  if (restartAt != 0 && static_cast<int32_t>(now - restartAt) >= 0) {
    if (clearBondsBeforeRestart && !ble.clearBonds()) {
      Serial.println(F("FACTORY_RESET_BOND_CLEAR_WARNING"));
    }
    Serial.print(F("RESTART_REQUESTED reason="));
    Serial.println(restartReason ? restartReason : "unspecified");
    Serial.flush();
    ESP.restart();
  }
  delay(2);
}
