// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <Arduino.h>
#include <FS.h>

enum BeaconMetricFlags : uint8_t {
  METRIC_LATENCY = 1 << 0, METRIC_DROP = 1 << 1, METRIC_DOWNLOAD = 1 << 2,
  METRIC_UPLOAD = 1 << 3, METRIC_SIGNAL = 1 << 4, METRIC_POWER = 1 << 5,
  METRIC_TIMESTAMP = 1 << 6
};

struct __attribute__((packed)) BeaconStoredSample {
  uint32_t magic = 0x4C534831;
  uint16_t version = 1;
  uint16_t size = 64;
  uint64_t sequence = 0;
  int64_t timestampMs = 0;
  uint64_t dishyCounter = 0;
  float latencyMs = NAN;
  float dropRate = NAN;
  float downloadMbps = NAN;
  float uploadMbps = NAN;
  float signal = NAN;
  float powerWatts = NAN;
  uint8_t connectivity = 0;
  uint8_t flags = 0;
  uint16_t bootId = 0;
  uint32_t crc = 0;
};
static_assert(sizeof(BeaconStoredSample) == 64, "BeaconStoredSample must remain 64 bytes");

class BeaconStorage {
 public:
  bool begin();
  bool append(BeaconStoredSample sample);
  bool flush();
  bool readAfter(uint64_t after, size_t limit, BeaconStoredSample* output,
                 size_t outputCapacity, size_t& count, bool& hasMore) const;
  bool acknowledgeThrough(uint64_t sequence);
  bool healthy() const { return healthy_; }
  bool pressure() const { return pressure_; }
  const char* lastError() const { return lastError_; }
  bool historyAvailable() const { return healthy_ && count_ > 0; }
  uint64_t oldestSequence() const;
  uint64_t newestSequence() const { return count_ ? nextSequence_ - 1 : 0; }
  uint64_t nextSequence() const { return nextSequence_; }
  uint64_t acknowledgedSequence() const { return acknowledgedSequence_; }
  uint64_t lastAckEpochMs() const { return lastAckEpochMs_; }
  uint64_t lastAckUptimeMs() const { return lastAckUptimeMs_; }
  uint64_t lastDishyCounter() const { return lastDishyCounter_; }
  uint16_t currentBootId() const { return currentBootId_; }
  uint64_t pendingRecords() const;
  uint32_t recordCount() const { return count_; }
  uint32_t capacity() const { return capacity_; }
  uint64_t usedBytes() const { return static_cast<uint64_t>(count_) * sizeof(BeaconStoredSample); }
  uint64_t capacityBytes() const { return static_cast<uint64_t>(capacity_) * sizeof(BeaconStoredSample); }

 private:
  struct __attribute__((packed)) Metadata {
    uint32_t magic; uint16_t version; uint16_t size; uint32_t generation;
    uint32_t capacity; uint32_t head; uint32_t count; uint64_t nextSequence;
    uint64_t acknowledgedSequence; uint64_t lastDishyCounter; uint64_t lastAckEpochMs;
    uint64_t lastAckUptimeMs; uint32_t crc;
  };
  struct __attribute__((packed)) AckState {
    uint32_t magic; uint16_t version; uint16_t size; uint64_t acknowledgedSequence;
    uint64_t nextSequence; uint64_t lastDishyCounter; uint64_t lastAckEpochMs; uint32_t crc;
  };
  bool loadMetadata();
  bool persistMetadata();
  bool loadAckState();
  bool persistAckState(uint64_t acknowledgedSequence, uint64_t lastAckEpochMs);
  bool ensureWriteHeadroom();
  bool reclaimAcknowledged();
  bool rebuildFromRecords();
  bool readSlot(uint32_t slot, BeaconStoredSample& sample) const;
  uint32_t slotForOldestOffset(uint32_t offset) const;
  static uint32_t crc32(const uint8_t* data, size_t length);
  static bool validRecord(const BeaconStoredSample& sample);
  bool healthy_ = false;
  bool pressure_ = false;
  const char* lastError_ = "NONE";
  uint8_t uncommitted_ = 0;
  uint16_t currentBootId_ = 0;
  uint32_t generation_ = 0, capacity_ = 0, head_ = 0, count_ = 0;
  uint64_t nextSequence_ = 1, acknowledgedSequence_ = 0, lastDishyCounter_ = 0;
  uint64_t lastAckEpochMs_ = 0, lastAckUptimeMs_ = 0;
};


