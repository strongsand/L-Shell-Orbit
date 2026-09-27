// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <Arduino.h>
#include <BLEDevice.h>
#include "BeaconConfigStore.h"
#include "BeaconIdentity.h"

enum class BleSetupState {
  INACTIVE,
  SETUP_BLE,
  WAITING_WIFI_CONFIG,
  WIFI_CONNECTING,
  COMPLETE,
  ERROR_STATE
};

class BleProvisioning {
 public:
  BleProvisioning(BeaconConfigStore& config, const BeaconIdentity& identity);
  void begin(bool keepOpenUntilConfigured = false);
  void stop();
  bool clearBonds();
  void update(uint32_t now);
  bool active() const { return active_; }
  bool wifiProvisioningReady() const { return state_ == BleSetupState::WAITING_WIFI_CONFIG; }
  bool takeSessionExpired();
  bool takePendingCredentials(WifiCredentials& output);
  void reportWifiResult(bool connected, const char* errorCode = nullptr);

 private:
  class ServerCallbacks;
  class SecurityCallbacks;
  class CharacteristicCallbacks;
  friend class ServerCallbacks;
  friend class SecurityCallbacks;
  friend class CharacteristicCallbacks;

  void handleWrite(BLECharacteristic* characteristic, const std::string& value);
  void handleConnected(const uint8_t* address);
  bool isBondedPeer(const uint8_t* address) const;
  void updateDeviceInfo(bool bondKnown);
  void startAdvertising();
  void stopAdvertising();
  void setSetupState(BleSetupState state);
  void setResult(bool ok, const char* code, const char* message);
  bool authorized(uint32_t now) const;
  const char* setupStateName() const;
  void clearStaging();

  BeaconConfigStore& config_;
  const BeaconIdentity& identity_;
  BLEServer* server_ = nullptr;
  BLECharacteristic* infoCharacteristic_ = nullptr;
  BLECharacteristic* stateCharacteristic_ = nullptr;
  BLECharacteristic* resultCharacteristic_ = nullptr;
  BLECharacteristic* ssidCharacteristic_ = nullptr;
  BLECharacteristic* passwordCharacteristic_ = nullptr;
  BLECharacteristic* applyCharacteristic_ = nullptr;
  BleSetupState state_ = BleSetupState::INACTIVE;
  WifiCredentials staged_;
  bool active_ = false;
  bool initialized_ = false;
  bool advertising_ = false;
  bool connected_ = false;
  bool secureSession_ = false;
  bool peerBonded_ = false;
  bool securityRequestLogged_ = false;
  bool alreadyBondedLogged_ = false;
  bool credentialsReady_ = false;
  uint32_t authorizationDeadline_ = 0;
  uint32_t setupDeadline_ = 0;
  bool sessionExpired_ = false;
  bool keepOpenUntilConfigured_ = false;
};


