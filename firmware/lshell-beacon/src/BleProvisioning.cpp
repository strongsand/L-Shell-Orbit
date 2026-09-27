// SPDX-License-Identifier: GPL-3.0-or-later
#include "BleProvisioning.h"

#include <BLE2902.h>
#include <BLESecurity.h>
#include <esp_gap_ble_api.h>
#include <cstdlib>
#include <cstring>
#include "BeaconConfig.h"

class BleProvisioning::ServerCallbacks : public BLEServerCallbacks {
 public:
  explicit ServerCallbacks(BleProvisioning& owner) : owner_(owner) {}
  void onConnect(BLEServer*) override {}
  void onConnect(BLEServer*, esp_ble_gatts_cb_param_t* param) override {
    owner_.connected_ = true;
    if (owner_.advertising_) Serial.println(F("BLE_ADVERTISING_STOP"));
    owner_.advertising_ = false;
    owner_.handleConnected(param->connect.remote_bda);
  }
  void onDisconnect(BLEServer*) override {
    Serial.println(F("BLE_DISCONNECTED"));
    owner_.connected_ = false;
    owner_.secureSession_ = false;
    owner_.peerBonded_ = false;
    owner_.securityRequestLogged_ = false;
    owner_.alreadyBondedLogged_ = false;
    owner_.clearStaging();
    if (owner_.active_ && owner_.state_ != BleSetupState::WIFI_CONNECTING &&
        owner_.state_ != BleSetupState::COMPLETE) {
      owner_.setupDeadline_ = owner_.keepOpenUntilConfigured_
          ? 0
          : millis() + BeaconConfig::kBleSetupWindowMs;
      owner_.setSetupState(BleSetupState::SETUP_BLE);
      owner_.startAdvertising();
    }
  }
 private:
  BleProvisioning& owner_;
};

class BleProvisioning::SecurityCallbacks : public BLESecurityCallbacks {
 public:
  explicit SecurityCallbacks(BleProvisioning& owner) : owner_(owner) {}
  uint32_t onPassKeyRequest() override { return 0; }
  void onPassKeyNotify(uint32_t) override {}
  bool onConfirmPIN(uint32_t) override { return false; }
  bool onSecurityRequest() override {
    if (owner_.secureSession_ || owner_.peerBonded_) {
      if (!owner_.alreadyBondedLogged_) {
        Serial.println(F("ALREADY_BONDED"));
        owner_.alreadyBondedLogged_ = true;
      }
    } else if (!owner_.securityRequestLogged_) {
      Serial.println(F("BOND_STATE_BONDING"));
      Serial.println(F("SECURITY_REQUEST"));
      owner_.securityRequestLogged_ = true;
    }
    return true;
  }
  void onAuthenticationComplete(esp_ble_auth_cmpl_t result) override {
    owner_.secureSession_ = result.success;
    owner_.peerBonded_ = result.success;
    owner_.updateDeviceInfo(result.success);
    Serial.println(result.success ? F("BOND_STATE_BONDED") : F("BOND_STATE_NONE"));
    Serial.println(result.success ? F("AUTH_COMPLETE_SUCCESS") : F("AUTH_COMPLETE_FAILED"));
    if (result.success) {
      owner_.clearStaging();
      owner_.authorizationDeadline_ = millis() + BeaconConfig::kSetupAuthorizationMs;
      owner_.setupDeadline_ = 0;
      Serial.println(F("PHYSICAL_CONFIRMATION_NOT_REQUIRED"));
      Serial.println(F("WIFI_PROVISIONING_READY"));
      owner_.setSetupState(BleSetupState::WAITING_WIFI_CONFIG);
    } else {
      owner_.clearStaging();
      owner_.setSetupState(BleSetupState::SETUP_BLE);
    }
  }
 private:
  BleProvisioning& owner_;
};

class BleProvisioning::CharacteristicCallbacks : public BLECharacteristicCallbacks {
 public:
  explicit CharacteristicCallbacks(BleProvisioning& owner) : owner_(owner) {}
  void onWrite(BLECharacteristic* characteristic) override {
    owner_.handleWrite(characteristic, characteristic->getValue());
  }
 private:
  BleProvisioning& owner_;
};

BleProvisioning::BleProvisioning(BeaconConfigStore& config, const BeaconIdentity& identity)
    : config_(config), identity_(identity) {}

void BleProvisioning::begin(bool keepOpenUntilConfigured) {
  if (active_) return;
  Serial.println(F("BLE_BEGIN"));
  if (!initialized_) {
    String name("L-Shell Beacon "); name += identity_.shortId();
    BLEDevice::init(name.c_str());
    BLEDevice::setSecurityCallbacks(new SecurityCallbacks(*this));
    auto* security = new BLESecurity();
    security->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_BOND);
    security->setCapability(ESP_IO_CAP_NONE);

    server_ = BLEDevice::createServer();
    server_->setCallbacks(new ServerCallbacks(*this));
    BLEService* service = server_->createService(BeaconConfig::kBleServiceUuid);
    auto* callbacks = new CharacteristicCallbacks(*this);

    infoCharacteristic_ = service->createCharacteristic(
        BeaconConfig::kBleDeviceInfoUuid, BLECharacteristic::PROPERTY_READ);
    infoCharacteristic_->setAccessPermissions(ESP_GATT_PERM_READ_ENCRYPTED);
    updateDeviceInfo(false);
    stateCharacteristic_ = service->createCharacteristic(
        BeaconConfig::kBleSetupStateUuid, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
    stateCharacteristic_->addDescriptor(new BLE2902());
    ssidCharacteristic_ = service->createCharacteristic(
        BeaconConfig::kBleWifiSsidUuid, BLECharacteristic::PROPERTY_WRITE);
    passwordCharacteristic_ = service->createCharacteristic(
        BeaconConfig::kBleWifiPasswordUuid, BLECharacteristic::PROPERTY_WRITE);
    applyCharacteristic_ = service->createCharacteristic(
        BeaconConfig::kBleApplyUuid, BLECharacteristic::PROPERTY_WRITE);
    ssidCharacteristic_->setAccessPermissions(ESP_GATT_PERM_WRITE_ENCRYPTED);
    passwordCharacteristic_->setAccessPermissions(ESP_GATT_PERM_WRITE_ENCRYPTED);
    applyCharacteristic_->setAccessPermissions(ESP_GATT_PERM_WRITE_ENCRYPTED);
    resultCharacteristic_ = service->createCharacteristic(
        BeaconConfig::kBleSetupResultUuid, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
    resultCharacteristic_->addDescriptor(new BLE2902());
    ssidCharacteristic_->setCallbacks(callbacks);
    passwordCharacteristic_->setCallbacks(callbacks);
    applyCharacteristic_->setCallbacks(callbacks);
    service->start();

    BLEAdvertising* advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(BeaconConfig::kBleServiceUuid);
    advertising->setScanResponse(true);
    initialized_ = true;
  }
  active_ = true;
  secureSession_ = false;
  peerBonded_ = false;
  securityRequestLogged_ = false;
  alreadyBondedLogged_ = false;
  sessionExpired_ = false;
  keepOpenUntilConfigured_ = keepOpenUntilConfigured;
  setupDeadline_ = keepOpenUntilConfigured ? 0 : millis() + BeaconConfig::kBleSetupWindowMs;
  setSetupState(BleSetupState::SETUP_BLE);
  startAdvertising();
}

void BleProvisioning::stop() {
  if (!active_) return;
  active_ = false;
  stopAdvertising();
  if (connected_ && server_) server_->disconnect(server_->getConnId());
  secureSession_ = false;
  peerBonded_ = false;
  setupDeadline_ = 0;
  keepOpenUntilConfigured_ = false;
  clearStaging();
  state_ = BleSetupState::INACTIVE;
}

bool BleProvisioning::clearBonds() {
  Serial.println(F("BLE_BONDS_CLEAR_START"));
  if (!initialized_) {
    String name("L-Shell Beacon "); name += identity_.shortId();
    BLEDevice::init(name.c_str());
  }
  const int count = esp_ble_get_bond_device_num();
  if (count <= 0) {
    Serial.println(F("BLE_BONDS_CLEAR_OK count=0"));
    return true;
  }
  auto* devices = static_cast<esp_ble_bond_dev_t*>(calloc(count, sizeof(esp_ble_bond_dev_t)));
  if (!devices) {
    Serial.println(F("BLE_BONDS_CLEAR_FAIL allocation"));
    return false;
  }
  int returned = count;
  bool success = esp_ble_get_bond_device_list(&returned, devices) == ESP_OK;
  int removed = 0;
  if (success) {
    for (int i = 0; i < returned; ++i) {
      if (esp_ble_remove_bond_device(devices[i].bd_addr) == ESP_OK) ++removed;
      else success = false;
    }
  }
  memset(devices, 0, count * sizeof(esp_ble_bond_dev_t));
  free(devices);
  if (success) {
    Serial.print(F("BLE_BONDS_CLEAR_OK count="));
    Serial.println(removed);
  } else {
    Serial.println(F("BLE_BONDS_CLEAR_FAIL remove"));
  }
  return success;
}

void BleProvisioning::startAdvertising() {
  if (!initialized_ || !active_ || connected_ || advertising_) return;
  BLEDevice::startAdvertising();
  advertising_ = true;
  Serial.println(F("BLE_ADVERTISING_START"));
}

void BleProvisioning::stopAdvertising() {
  if (!initialized_ || !advertising_) return;
  BLEDevice::getAdvertising()->stop();
  advertising_ = false;
  Serial.println(F("BLE_ADVERTISING_STOP"));
}

void BleProvisioning::update(uint32_t now) {
  if (state_ == BleSetupState::WAITING_WIFI_CONFIG && !authorized(now)) {
    Serial.println(F("WIFI_PROVISIONING_TIMEOUT"));
    clearStaging();
    if (keepOpenUntilConfigured_) {
      setSetupState(BleSetupState::SETUP_BLE);
      if (connected_ && server_) server_->disconnect(server_->getConnId());
    } else {
      stop();
      sessionExpired_ = true;
    }
  }
  if (setupDeadline_ != 0 && static_cast<int32_t>(now - setupDeadline_) >= 0 &&
      state_ == BleSetupState::SETUP_BLE) {
    Serial.println(F("BLE_SETUP_SESSION_TIMEOUT"));
    stop();
    sessionExpired_ = true;
  }
}

bool BleProvisioning::takeSessionExpired() {
  const bool expired = sessionExpired_;
  sessionExpired_ = false;
  return expired;
}

bool BleProvisioning::takePendingCredentials(WifiCredentials& output) {
  if (!credentialsReady_) return false;
  output = staged_;
  staged_.clear();
  credentialsReady_ = false;
  setupDeadline_ = 0;
  setSetupState(BleSetupState::WIFI_CONNECTING);
  return true;
}

void BleProvisioning::reportWifiResult(bool connected, const char* errorCode) {
  if (connected) {
    setResult(true, "OK", "Wi-Fi connected");
    setSetupState(BleSetupState::COMPLETE);
  } else {
    setResult(false, errorCode ? errorCode : "WIFI_AUTH_FAILED", "Wi-Fi connection failed");
    setSetupState(BleSetupState::ERROR_STATE);
  }
}

void BleProvisioning::handleWrite(BLECharacteristic* characteristic, const std::string& value) {
  if (!secureSession_) {
    setResult(false, "BLE_SECURITY_REQUIRED", "encrypted BLE session required");
    return;
  }
  const String text(value.c_str());
  if (characteristic == applyCharacteristic_ && text == "REQUEST_SETUP") {
    clearStaging();
    authorizationDeadline_ = millis() + BeaconConfig::kSetupAuthorizationMs;
    Serial.println(F("PHYSICAL_CONFIRMATION_NOT_REQUIRED"));
    Serial.println(F("WIFI_PROVISIONING_READY"));
    setSetupState(BleSetupState::WAITING_WIFI_CONFIG);
    setResult(true, "OK", "Wi-Fi provisioning ready");
    return;
  }
  if (characteristic == applyCharacteristic_ && text == "CANCEL") {
    clearStaging();
    setSetupState(BleSetupState::SETUP_BLE);
    return;
  }
  if (!authorized(millis())) {
    setResult(false, "SETUP_SESSION_EXPIRED", "secure setup session expired");
    return;
  }
  if (characteristic == ssidCharacteristic_) staged_.ssid = text;
  else if (characteristic == passwordCharacteristic_) staged_.password = text;
  else if (characteristic == applyCharacteristic_ && text == "APPLY") {
    if (!staged_.valid()) {
      setResult(false, staged_.ssid.length() ? "INVALID_PASSWORD" : "INVALID_SSID", "invalid Wi-Fi credentials");
      return;
    }
    if (!config_.saveWifi(staged_)) {
      setResult(false, "NVS_ERROR", "could not store Wi-Fi configuration");
      return;
    }
    credentialsReady_ = true;
  } else setResult(false, "INVALID_COMMAND", "unknown setup command");
}

void BleProvisioning::handleConnected(const uint8_t* address) {
  Serial.println(F("BLE_CONNECTED"));
  secureSession_ = false;
  securityRequestLogged_ = false;
  alreadyBondedLogged_ = false;
  peerBonded_ = isBondedPeer(address);
  Serial.println(peerBonded_ ? F("BOND_STATE_BONDED") : F("BOND_STATE_NONE"));
  updateDeviceInfo(peerBonded_);
  if (peerBonded_) {
    Serial.println(F("ALREADY_BONDED"));
    alreadyBondedLogged_ = true;
  }
}

bool BleProvisioning::isBondedPeer(const uint8_t* address) const {
  if (!address) return false;
  int count = esp_ble_get_bond_device_num();
  if (count <= 0) return false;
  auto* devices = static_cast<esp_ble_bond_dev_t*>(calloc(count, sizeof(esp_ble_bond_dev_t)));
  if (!devices) return false;
  int returned = count;
  const esp_err_t status = esp_ble_get_bond_device_list(&returned, devices);
  bool found = false;
  if (status == ESP_OK) {
    for (int i = 0; i < returned; ++i) {
      if (memcmp(devices[i].bd_addr, address, sizeof(esp_bd_addr_t)) == 0) {
        found = true;
        break;
      }
    }
  }
  free(devices);
  return found;
}

void BleProvisioning::updateDeviceInfo(bool bondKnown) {
  if (!infoCharacteristic_) return;
  String infoJson("{\"beacon_id\":\""); infoJson += identity_.id();
  infoJson += F("\",\"firmware_version\":\""); infoJson += BeaconConfig::kFirmwareVersion;
  infoJson += F("\",\"protocol_version\":"); infoJson += String(BeaconConfig::kProtocolVersion);
  infoJson += F(",\"hardware\":\""); infoJson += BeaconConfig::kHardware;
  infoJson += F("\",\"bond_known\":"); infoJson += (bondKnown ? F("true") : F("false"));
  infoJson += '}';
  infoCharacteristic_->setValue(infoJson.c_str());
}

void BleProvisioning::setSetupState(BleSetupState state) {
  state_ = state;
  if (!stateCharacteristic_) return;
  stateCharacteristic_->setValue(setupStateName());
  stateCharacteristic_->notify();
}

void BleProvisioning::setResult(bool ok, const char* code, const char* message) {
  if (!resultCharacteristic_) return;
  String json("{\"ok\":"); json += ok ? "true" : "false";
  json += F(",\"code\":\""); json += code;
  json += F("\",\"message\":\""); json += message; json += F("\"}");
  resultCharacteristic_->setValue(json.c_str());
  resultCharacteristic_->notify();
}

bool BleProvisioning::authorized(uint32_t now) const {
  return secureSession_ && peerBonded_ && state_ == BleSetupState::WAITING_WIFI_CONFIG &&
         static_cast<int32_t>(authorizationDeadline_ - now) > 0;
}

const char* BleProvisioning::setupStateName() const {
  switch (state_) {
    case BleSetupState::SETUP_BLE: return "SETUP_BLE";
    case BleSetupState::WAITING_WIFI_CONFIG: return "WAITING_WIFI_CONFIG";
    case BleSetupState::WIFI_CONNECTING: return "WIFI_CONNECTING";
    case BleSetupState::COMPLETE: return "COMPLETE";
    case BleSetupState::ERROR_STATE: return "ERROR";
    default: return "INACTIVE";
  }
}

void BleProvisioning::clearStaging() {
  staged_.clear();
  credentialsReady_ = false;
  authorizationDeadline_ = 0;
}


