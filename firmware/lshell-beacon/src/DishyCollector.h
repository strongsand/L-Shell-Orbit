// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <Arduino.h>
#include "BeaconStorage.h"

class DishyCollector {
 public:
  explicit DishyCollector(BeaconStorage& storage) : storage_(storage) {}
  bool begin();
  void update(uint32_t now);
  bool implemented() const { return true; }
  bool reachableKnown() const { return reachableKnown_; }
  bool reachable() const { return reachable_; }
  bool recording() const { return reachable_ && storage_.healthy() && lastFetchOk_; }
  uint64_t lastCollectionEpochMs() const { return lastCollectionEpochMs_; }
  uint64_t lastSampleEpochMs() const { return lastSampleEpochMs_; }
  uint64_t lastCollectionUptimeMs() const { return lastCollectionUptimeMs_; }
  uint64_t lastSampleUptimeMs() const { return lastSampleUptimeMs_; }

 private:
  bool probe();
  bool startFetchTask();
  void consumeFetchResult(uint32_t now);
  bool storeHistory(void* parsedHistory);
  static void fetchTaskEntry(void* context);
  BeaconStorage& storage_;
  bool reachableKnown_ = false, reachable_ = false, lastFetchOk_ = false;
  bool fetchRunning_ = false;
  void* fetchResultQueue_ = nullptr;
  uint32_t nextProbeAt_ = 0, nextHistoryAt_ = 0;
  uint64_t lastCollectionEpochMs_ = 0, lastSampleEpochMs_ = 0;
  uint64_t lastCollectionUptimeMs_ = 0, lastSampleUptimeMs_ = 0;
};


