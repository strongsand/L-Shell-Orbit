// SPDX-License-Identifier: GPL-3.0-or-later
#include "BeaconHttpServer.h"

#include "BeaconConfig.h"
#include <esp_timer.h>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <memory>
#include <new>

BeaconHttpServer::BeaconHttpServer(const BeaconIdentity& identity, const WifiConnectionManager& wifi,
                                   BeaconStorage& storage, const DishyCollector& dishy,
                                   BeaconState& state, LedController& led)
    : server_(BeaconConfig::kHttpPort), identity_(identity), wifi_(wifi), storage_(storage),
      dishy_(dishy), state_(state), led_(led) {}

void BeaconHttpServer::begin() {
  server_.on("/v1/info", HTTP_GET, [this]() { sendInfo(); });
  server_.on("/v1/status", HTTP_GET, [this]() { sendStatus(); });
  server_.on("/v1/history/info", HTTP_GET, [this]() { sendHistoryInfo(); });
  server_.on("/v1/history", HTTP_GET, [this]() { sendHistory(); });
  server_.on("/v1/history/ack", HTTP_POST, [this]() { acceptHistoryAck(); });
  server_.onNotFound([this]() { sendNotFound(); });
  server_.begin();
}

void BeaconHttpServer::update() { server_.handleClient(); }
void BeaconHttpServer::end() { server_.stop(); }

void BeaconHttpServer::sendInfo() {
  String json;
  json.reserve(240);
  json += F("{\"beacon_id\":\""); json += identity_.id();
  json += F("\",\"firmware_version\":\""); json += BeaconConfig::kFirmwareVersion;
  json += F("\",\"protocol_version\":"); json += String(BeaconConfig::kProtocolVersion);
  json += F(",\"hardware\":\""); json += BeaconConfig::kHardware;
  json += F("\",\"capabilities\":[\"ble_provisioning\",\"status\",\"history_v1\"]}");
  server_.send(200, "application/json; charset=utf-8", json);
}

void BeaconHttpServer::sendStatus() {
  const uint64_t uptimeMs=static_cast<uint64_t>(esp_timer_get_time())/1000ULL;
  String json;
  json.reserve(520);
  json += F("{\"state\":\""); json += beaconStateName(state_);
  json += F("\",\"uptime_seconds\":"); json += String(uptimeMs / 1000ULL);
  json += F(",\"beacon_uptime_ms\":"); json += String(uptimeMs);
  json += F(",\"boot_id\":"); json += String(storage_.currentBootId());
  json += F(",\"wifi_connected\":"); json += wifi_.state() == WifiConnectionState::CONNECTED ? "true" : "false";
  json += F(",\"wifi_rssi\":"); json += wifi_.state() == WifiConnectionState::CONNECTED ? String(wifi_.rssi()) : "null";
  json += F(",\"paired\":false,\"recording\":"); json += dishy_.recording() ? "true" : "false";
  json += F(",\"dish_reachable\":"); json += dishy_.reachableKnown() ? (dishy_.reachable() ? "true" : "false") : "null";
  json += F(",\"last_collect_ms\":"); json += dishy_.lastCollectionEpochMs() ? String(dishy_.lastCollectionEpochMs()) : "null";
  json += F(",\"last_sample_ms\":"); json += dishy_.lastSampleEpochMs() ? String(dishy_.lastSampleEpochMs()) : "null";
  json += F(",\"last_collect_uptime_ms\":"); json += dishy_.lastCollectionUptimeMs() ? String(dishy_.lastCollectionUptimeMs()) : "null";
  json += F(",\"last_sample_uptime_ms\":"); json += dishy_.lastSampleUptimeMs() ? String(dishy_.lastSampleUptimeMs()) : "null";
  json += F(",\"last_sync_ms\":"); json += storage_.lastAckEpochMs() ? String(storage_.lastAckEpochMs()) : "null";
  json += F(",\"last_sync_uptime_ms\":"); json += storage_.lastAckUptimeMs() ? String(storage_.lastAckUptimeMs()) : "null";
  json += F(",\"pending_records\":"); json += String(storage_.pendingRecords());
  json += F(",\"record_count\":"); json += String(storage_.recordCount());
  json += F(",\"oldest_sequence\":"); json += String(storage_.oldestSequence());
  json += F(",\"newest_sequence\":"); json += String(storage_.newestSequence());
  json += F(",\"storage_used_bytes\":"); json += String(storage_.usedBytes());
  json += F(",\"storage_capacity_bytes\":"); json += String(storage_.capacityBytes());
  json += F(",\"storage_healthy\":"); json += storage_.healthy() ? "true}" : "false}";
  server_.send(200, "application/json; charset=utf-8", json);
}

void BeaconHttpServer::sendHistoryInfo() {
  String json; json.reserve(300);
  json += F("{\"protocol_version\":"); json += String(BeaconConfig::kProtocolVersion);
  json += F(",\"record_version\":1,\"oldest_sequence\":"); json += String(storage_.oldestSequence());
  json += F(",\"newest_sequence\":"); json += String(storage_.newestSequence());
  json += F(",\"record_count\":"); json += String(storage_.recordCount());
  json += F(",\"capacity\":"); json += String(storage_.capacity());
  json += F(",\"recording\":"); json += dishy_.recording() ? "true" : "false";
  json += F(",\"dish_reachable\":"); json += dishy_.reachableKnown() ? (dishy_.reachable() ? "true" : "false") : "null";
  json += F(",\"acknowledged_sequence\":"); json += String(storage_.acknowledgedSequence()); json += '}';
  server_.send(200, "application/json; charset=utf-8", json);
}

static void appendMetric(String& json, const char* name, float value, bool valid) {
  json += '\"'; json += name; json += F("\":");
  if (valid && isfinite(value)) json += String(value, 4); else json += F("null");
}

void BeaconHttpServer::sendHistory() {
  Serial.println(F("SYNC_REQUEST"));
  Serial.println(F("SYNC_HISTORY_READ_START"));
  const String afterArgument=server_.arg("after");
  uint64_t after=server_.hasArg("after")&&!afterArgument.startsWith("-")?strtoull(afterArgument.c_str(),nullptr,10):0;
  int requestedLimit=server_.hasArg("limit")?server_.arg("limit").toInt():500;
  size_t limit=static_cast<size_t>(constrain(requestedLimit,1,500));
  std::unique_ptr<BeaconStoredSample[]> records(new (std::nothrow) BeaconStoredSample[limit]);
  if (!records) { Serial.println(F("SYNC_HISTORY_READ_ERROR=NO_MEMORY"));server_.send(503, "application/json", "{\"ok\":false,\"code\":\"NO_MEMORY\"}"); return; }
  size_t count = 0; bool hasMore = false;
  if (!storage_.readAfter(after, limit, records.get(), limit, count, hasMore)) {
    Serial.print(F("SYNC_HISTORY_READ_ERROR="));Serial.println(storage_.lastError());
    server_.send(500, "application/json", "{\"ok\":false,\"code\":\"STORAGE_ERROR\"}"); return;
  }
  Serial.print(F("SYNC_HISTORY_PAGE_COUNT="));Serial.println(count);
  setSyncVisual(true);
  server_.setContentLength(CONTENT_LENGTH_UNKNOWN);
  server_.send(200, "application/json; charset=utf-8", "");
  String prefix=F("{\"record_version\":1,\"beacon_uptime_ms\":");prefix+=String(static_cast<uint64_t>(esp_timer_get_time())/1000ULL);prefix+=F(",\"beacon_boot_id\":");prefix+=String(storage_.currentBootId());prefix+=F(",\"from_sequence\":");prefix+=count?String(records[0].sequence):String(after);
  prefix+=F(",\"records\":[");server_.sendContent(prefix);
  for(size_t i=0;i<count;++i){const auto& r=records[i];String row;row.reserve(300);if(i)row+=',';
    row+=F("{\"sequence\":");row+=String(r.sequence);row+=F(",\"timestamp_ms\":");row+=(r.flags&METRIC_TIMESTAMP)?String(r.timestampMs):String("null");
    row+=F(",\"capture_uptime_ms\":");row+=(r.flags&METRIC_TIMESTAMP)?String("null"):String(r.timestampMs);
    row+=F(",\"boot_id\":");row+=String(r.bootId);
    row+=F(",\"dish_counter\":");row+=String(r.dishyCounter);row+=',';
    appendMetric(row,"latency_ms",r.latencyMs,r.flags&METRIC_LATENCY);row+=',';
    appendMetric(row,"drop_rate",r.dropRate,r.flags&METRIC_DROP);row+=',';
    appendMetric(row,"download_mbps",r.downloadMbps,r.flags&METRIC_DOWNLOAD);row+=',';
    appendMetric(row,"upload_mbps",r.uploadMbps,r.flags&METRIC_UPLOAD);row+=',';
    appendMetric(row,"signal",r.signal,r.flags&METRIC_SIGNAL);row+=',';
    appendMetric(row,"power_watts",r.powerWatts,r.flags&METRIC_POWER);
    row+=F(",\"connectivity\":");row+=String(r.connectivity);row+='}';server_.sendContent(row);led_.update(millis());yield();}
  String suffix=F("],\"to_sequence\":");suffix+=count?String(records[count-1].sequence):String(after);
  suffix+=F(",\"next_sequence\":");suffix+=count?String(records[count-1].sequence):String(after);
  suffix+=F(",\"has_more\":");suffix+=hasMore?"true}":"false}";server_.sendContent(suffix);server_.sendContent("");
  Serial.println(F("SYNC_PAGE_SENT"));
  setSyncVisual(false);
}

void BeaconHttpServer::setSyncVisual(bool active) {
  if (active) state_ = BeaconState::SYNCING;
  else if (!storage_.healthy() || storage_.pressure()) state_ = BeaconState::STORAGE_WARNING;
  else if (dishy_.reachableKnown() && !dishy_.reachable()) state_ = BeaconState::DISHY_UNAVAILABLE;
  else state_ = dishy_.recording() ? BeaconState::RECORDING : BeaconState::READY;
  led_.setState(state_);
}

void BeaconHttpServer::acceptHistoryAck() {
  const String body=server_.arg("plain");const int marker=body.indexOf("sequence");const int colon=marker<0?-1:body.indexOf(':',marker);
  if(colon<0){server_.send(400,"application/json","{\"ok\":false,\"code\":\"INVALID_ACK\"}");return;}
  int valueStart=colon+1;while(valueStart<body.length()&&std::isspace(static_cast<unsigned char>(body[valueStart])))++valueStart;
  if(valueStart>=body.length()||!std::isdigit(static_cast<unsigned char>(body[valueStart]))){server_.send(400,"application/json","{\"ok\":false,\"code\":\"INVALID_ACK\"}");return;}
  char* end=nullptr;const uint64_t sequence=strtoull(body.c_str()+valueStart,&end,10);
  if(end==body.c_str()+valueStart){server_.send(400,"application/json","{\"ok\":false,\"code\":\"INVALID_ACK\"}");return;}
  Serial.println(F("SYNC_ACK_RECEIVED"));if(!storage_.acknowledgeThrough(sequence)){Serial.print(F("SYNC_ACK_PERSIST_ERROR="));Serial.println(storage_.lastError());server_.send(500,"application/json","{\"ok\":false,\"code\":\"STORAGE_ERROR\"}");return;}
  Serial.println(F("SYNC_ACK"));server_.send(200,"application/json","{\"ok\":true}");
}

void BeaconHttpServer::sendNotFound() {
  server_.send(404, "application/json; charset=utf-8",
               "{\"ok\":false,\"code\":\"NOT_FOUND\",\"message\":\"endpoint not found\"}");
}

