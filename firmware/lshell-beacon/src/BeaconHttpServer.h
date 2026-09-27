// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <WebServer.h>
#include "BeaconIdentity.h"
#include "BeaconState.h"
#include "BeaconStorage.h"
#include "DishyCollector.h"
#include "WifiConnectionManager.h"
#include "LedController.h"

class BeaconHttpServer {
 public:
  BeaconHttpServer(const BeaconIdentity& identity, const WifiConnectionManager& wifi,
                   BeaconStorage& storage, const DishyCollector& dishy,
                   BeaconState& state, LedController& led);
  void begin();
  void update();
  void end();

 private:
  void sendInfo();
  void sendStatus();
  void sendHistoryInfo();
  void sendHistory();
  void acceptHistoryAck();
  void setSyncVisual(bool active);
  void sendNotFound();
  WebServer server_;
  const BeaconIdentity& identity_;
  const WifiConnectionManager& wifi_;
  BeaconStorage& storage_;
  const DishyCollector& dishy_;
  BeaconState& state_;
  LedController& led_;
};


