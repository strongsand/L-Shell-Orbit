// SPDX-License-Identifier: GPL-3.0-or-later
#include "BeaconStorage.h"
#include <LittleFS.h>
#include <Preferences.h>
#include <esp_timer.h>
#include <algorithm>
#include <stddef.h>
#include <ctime>

namespace { constexpr char kDataPath[]="/history.bin"; constexpr char kMetaPath0[]="/history.0.meta"; constexpr char kMetaPath1[]="/history.1.meta";
constexpr uint32_t kMetaMagic=0x4C53484D; constexpr uint16_t kMetaVersion=2;
constexpr uint32_t kAckMagic=0x4C534841; constexpr uint16_t kAckVersion=1;
constexpr size_t kReserveBytes=48*1024, kMinimumWriteHeadroomBytes=64*1024, kReclaimTargetBytes=128*1024; }

uint32_t BeaconStorage::crc32(const uint8_t* p,size_t n){uint32_t c=0xFFFFFFFFu;while(n--){c^=*p++;for(uint8_t b=0;b<8;++b)c=(c>>1)^(0xEDB88320u&(0u-(c&1u)));}return ~c;}

bool BeaconStorage::begin(){
  // Format an erased partition only on first initialization. Once initialized, a mount
  // failure is surfaced as STORAGE_WARNING so sequence IDs cannot silently restart for
  // the same beacon_id after an automatic reformat.
  Preferences marker;const bool markerReady=marker.begin("history",false);if(!markerReady)return false;
  const bool initialized=marker.getBool("initialized",false);
  uint32_t bootGeneration=marker.getUInt("boot",0)+1;if(bootGeneration>65535)bootGeneration=1;
  bool mounted=LittleFS.begin(false);if(!mounted&&!initialized)mounted=LittleFS.begin(true);
  if(!mounted){marker.end();return false;}
  const bool marked=(initialized||marker.putBool("initialized",true)==1)&&marker.putUInt("boot",bootGeneration)==4;
  marker.end();if(!marked)return false;currentBootId_=static_cast<uint16_t>(bootGeneration);
  const size_t total=LittleFS.totalBytes();
  if(total<=kReserveBytes+sizeof(BeaconStoredSample)*64)return false;
  capacity_=static_cast<uint32_t>((total-kReserveBytes)/sizeof(BeaconStoredSample));
  Serial.print(F("STORAGE_TOTAL_BYTES="));Serial.println(total);Serial.print(F("STORAGE_USED_BYTES="));Serial.println(LittleFS.usedBytes());
  Serial.print(F("STORAGE_RESERVED_BYTES="));Serial.println(kReserveBytes);Serial.print(F("STORAGE_CAPACITY_RECORDS="));Serial.println(capacity_);
  const bool metadataLoaded=loadMetadata();
  healthy_=rebuildFromRecords();
  if(!metadataLoaded)acknowledgedSequence_=0;
  loadAckState();
  // Uptime is meaningful only within the current boot. The ACK sequence remains durable.
  lastAckUptimeMs_=0;
  if(!healthy_)return false;
  if(!ensureWriteHeadroom())return true;
  return persistMetadata();
}

bool BeaconStorage::loadMetadata(){
  Metadata best{};bool found=false;const char* paths[2]={kMetaPath0,kMetaPath1};
  for(uint8_t i=0;i<2;++i){File f=LittleFS.open(paths[i],FILE_READ);if(!f)continue;Metadata m{};const bool read=f.read(reinterpret_cast<uint8_t*>(&m),sizeof(m))==sizeof(m);f.close();if(!read)continue;
    const uint32_t expected=crc32(reinterpret_cast<const uint8_t*>(&m),offsetof(Metadata,crc));
    if(m.magic!=kMetaMagic||m.version!=kMetaVersion||m.size!=sizeof(Metadata)||m.crc!=expected||m.capacity!=capacity_||m.head>=capacity_||m.count>capacity_)continue;
    if(!found||m.generation>best.generation){best=m;found=true;}}
  if(!found)return false;generation_=best.generation;head_=best.head;count_=best.count;nextSequence_=best.nextSequence;
  acknowledgedSequence_=best.acknowledgedSequence;lastDishyCounter_=best.lastDishyCounter;lastAckEpochMs_=best.lastAckEpochMs;lastAckUptimeMs_=best.lastAckUptimeMs;return nextSequence_>0;
}

bool BeaconStorage::persistMetadata(){
  if(!ensureWriteHeadroom()){lastError_="LOW_SPACE";return false;}
  Metadata m{kMetaMagic,kMetaVersion,sizeof(Metadata),++generation_,capacity_,head_,count_,nextSequence_,acknowledgedSequence_,lastDishyCounter_,lastAckEpochMs_,lastAckUptimeMs_,0};
  m.crc=crc32(reinterpret_cast<const uint8_t*>(&m),offsetof(Metadata,crc));const char* path=(generation_&1)?kMetaPath1:kMetaPath0;
  File f=LittleFS.open(path,FILE_WRITE);if(!f){lastError_="METADATA_OPEN_FAILED";return false;}const bool ok=f.write(reinterpret_cast<const uint8_t*>(&m),sizeof(m))==sizeof(m);f.flush();f.close();if(ok){uncommitted_=0;lastError_="NONE";}else lastError_="METADATA_WRITE_FAILED";return ok;
}

bool BeaconStorage::loadAckState(){
  Preferences prefs;if(!prefs.begin("history",true))return false;AckState state{};const bool present=prefs.getBytesLength("ack_state")==sizeof(state)&&prefs.getBytes("ack_state",&state,sizeof(state))==sizeof(state);prefs.end();
  if(!present||state.magic!=kAckMagic||state.version!=kAckVersion||state.size!=sizeof(state)||state.crc!=crc32(reinterpret_cast<const uint8_t*>(&state),offsetof(AckState,crc)))return false;
  acknowledgedSequence_=std::max(acknowledgedSequence_,state.acknowledgedSequence);nextSequence_=std::max(nextSequence_,state.nextSequence);lastDishyCounter_=std::max(lastDishyCounter_,state.lastDishyCounter);lastAckEpochMs_=std::max(lastAckEpochMs_,state.lastAckEpochMs);return true;
}

bool BeaconStorage::persistAckState(uint64_t acknowledgedSequence,uint64_t lastAckEpochMs){
  AckState state{kAckMagic,kAckVersion,sizeof(AckState),acknowledgedSequence,nextSequence_,lastDishyCounter_,lastAckEpochMs,0};state.crc=crc32(reinterpret_cast<const uint8_t*>(&state),offsetof(AckState,crc));
  Preferences prefs;if(!prefs.begin("history",false)){lastError_="ACK_NVS_OPEN_FAILED";return false;}const bool ok=prefs.putBytes("ack_state",&state,sizeof(state))==sizeof(state);prefs.end();if(!ok)lastError_="ACK_NVS_WRITE_FAILED";return ok;
}

bool BeaconStorage::ensureWriteHeadroom(){
  // The bundled LittleFS reaches a broken divide-by-zero diagnostic if an
  // allocation is attempted after all blocks are exhausted. Reads remain safe.
  const size_t total=LittleFS.totalBytes();const size_t used=LittleFS.usedBytes();const size_t freeBytes=used<=total?total-used:0;
  if(total&&used<=total&&freeBytes>=kMinimumWriteHeadroomBytes)return true;
  if(!pressure_){pressure_=true;Serial.println(F("STORAGE_PRESSURE_ENTER"));Serial.print(F("STORAGE_TOTAL_BYTES="));Serial.println(total);Serial.print(F("STORAGE_USED_BYTES="));Serial.println(used);Serial.print(F("STORAGE_FREE_BYTES="));Serial.println(freeBytes);Serial.print(F("STORAGE_REQUIRED_HEADROOM="));Serial.println(kMinimumWriteHeadroomBytes);}
  lastError_="LOW_SPACE";return false;
}

bool BeaconStorage::reclaimAcknowledged(){
  if(!pressure_)return true;const uint64_t oldest=oldestSequence(),newest=newestSequence();uint32_t acked=0;
  if(count_&&acknowledgedSequence_>=oldest)acked=static_cast<uint32_t>(std::min<uint64_t>(acknowledgedSequence_,newest)-oldest+1);
  Serial.println(F("STORAGE_RECLAIM_START"));Serial.print(F("STORAGE_ACKED_RECORDS_AVAILABLE="));Serial.println(acked);
  // With a nearly full filesystem there is no safe room for a temporary compacted
  // copy. Wait until every record is ACKed, then remove only the fully drained file.
  if(!count_||acked<count_)return true;
  File f=LittleFS.open(kDataPath,FILE_READ);const size_t reclaimedBytes=f?f.size():0;if(f)f.close();
  if(LittleFS.exists(kDataPath)&&!LittleFS.remove(kDataPath)){lastError_="RECLAIM_REMOVE_FAILED";return false;}
  const uint32_t reclaimedRecords=count_;head_=0;count_=0;uncommitted_=0;
  const size_t total=LittleFS.totalBytes(),used=LittleFS.usedBytes(),freeBytes=used<=total?total-used:0;
  Serial.print(F("STORAGE_RECLAIMED_RECORDS="));Serial.println(reclaimedRecords);Serial.print(F("STORAGE_RECLAIMED_BYTES="));Serial.println(reclaimedBytes);Serial.print(F("STORAGE_FREE_AFTER="));Serial.println(freeBytes);
  if(freeBytes<kReclaimTargetBytes){lastError_="RECLAIM_TARGET_NOT_REACHED";return false;}
  pressure_=false;Serial.println(F("STORAGE_PRESSURE_EXIT"));Serial.println(F("STORAGE_RECOVERED"));
  if(!persistMetadata()){pressure_=true;return false;}lastError_="NONE";return true;
}

bool BeaconStorage::rebuildFromRecords(){
  File f=LittleFS.open(kDataPath,FILE_READ);if(!f){head_=count_=0;nextSequence_=std::max<uint64_t>(nextSequence_,1);lastDishyCounter_=0;return true;}
  const uint32_t slots=std::min<uint32_t>(capacity_,f.size()/sizeof(BeaconStoredSample));uint64_t newest=0;uint32_t newestSlot=0;BeaconStoredSample newestRecord{};
  for(uint32_t slot=0;slot<slots;++slot){BeaconStoredSample s{};if(!f.seek(static_cast<size_t>(slot)*sizeof(s),SeekSet)||f.read(reinterpret_cast<uint8_t*>(&s),sizeof(s))!=sizeof(s)||!validRecord(s))continue;if(s.sequence>newest){newest=s.sequence;newestSlot=slot;newestRecord=s;}}
  if(!newest){head_=count_=0;nextSequence_=std::max<uint64_t>(nextSequence_,1);lastDishyCounter_=0;f.close();return true;}
  // Retain only the newest contiguous suffix. If one slot was torn by power loss,
  // older records before that gap are discarded instead of exposing a false range.
  count_=1;uint64_t expected=newest-1;uint32_t slot=newestSlot;
  while(count_<slots&&expected>0){slot=(slot+capacity_-1)%capacity_;BeaconStoredSample s{};if(!f.seek(static_cast<size_t>(slot)*sizeof(s),SeekSet)||f.read(reinterpret_cast<uint8_t*>(&s),sizeof(s))!=sizeof(s)||!validRecord(s)||s.sequence!=expected)break;++count_;--expected;}
  head_=(newestSlot+1)%capacity_;nextSequence_=newest+1;lastDishyCounter_=newestRecord.dishyCounter;
  acknowledgedSequence_=std::min(acknowledgedSequence_,newest);f.close();return true;
}

bool BeaconStorage::append(BeaconStoredSample s){
  if(!healthy_||!capacity_||pressure_||(uncommitted_==0&&!ensureWriteHeadroom()))return false;s.magic=0x4C534831;s.version=1;s.size=sizeof(s);s.sequence=nextSequence_;s.bootId=currentBootId_;s.crc=0;s.crc=crc32(reinterpret_cast<const uint8_t*>(&s),offsetof(BeaconStoredSample,crc));
  File f=LittleFS.open(kDataPath,LittleFS.exists(kDataPath)?"r+":FILE_WRITE);if(!f||!f.seek(static_cast<size_t>(head_)*sizeof(s),SeekSet)||f.write(reinterpret_cast<const uint8_t*>(&s),sizeof(s))!=sizeof(s)){if(f)f.close();lastError_="HISTORY_WRITE_FAILED";pressure_=true;return false;}
  f.flush();f.close();head_=(head_+1)%capacity_;if(count_<capacity_)++count_;++nextSequence_;lastDishyCounter_=s.dishyCounter;
  if(++uncommitted_>=16&&!persistMetadata())return false;lastError_="NONE";return true;
}

bool BeaconStorage::flush(){if(!healthy_||!uncommitted_)return healthy_;return persistMetadata();}

uint32_t BeaconStorage::slotForOldestOffset(uint32_t offset)const{const uint32_t oldest=(head_+capacity_-count_)%capacity_;return(oldest+offset)%capacity_;}
bool BeaconStorage::readSlot(uint32_t slot,BeaconStoredSample& s)const{File f=LittleFS.open(kDataPath,FILE_READ);if(!f||!f.seek(static_cast<size_t>(slot)*sizeof(s),SeekSet))return false;const bool ok=f.read(reinterpret_cast<uint8_t*>(&s),sizeof(s))==sizeof(s);f.close();return ok;}
bool BeaconStorage::validRecord(const BeaconStoredSample& s){return s.magic==0x4C534831&&s.version==1&&s.size==sizeof(s)&&s.crc==crc32(reinterpret_cast<const uint8_t*>(&s),offsetof(BeaconStoredSample,crc));}

bool BeaconStorage::readAfter(uint64_t after,size_t limit,BeaconStoredSample* out,size_t cap,size_t& n,bool& more)const{
  n=0;more=false;if(!healthy_){const_cast<BeaconStorage*>(this)->lastError_="STORAGE_NOT_READABLE";return false;}if(!out||!cap){const_cast<BeaconStorage*>(this)->lastError_="INVALID_READ_ARGUMENT";return false;}const size_t wanted=std::min<size_t>(std::min<size_t>(limit,cap),500);File f=LittleFS.open(kDataPath,FILE_READ);if(!f&&count_){const_cast<BeaconStorage*>(this)->lastError_="HISTORY_OPEN_FAILED";return false;}
  for(uint32_t i=0;i<count_;++i){BeaconStoredSample s{};const uint32_t slot=slotForOldestOffset(i);if(!f.seek(static_cast<size_t>(slot)*sizeof(s),SeekSet)||f.read(reinterpret_cast<uint8_t*>(&s),sizeof(s))!=sizeof(s)||!validRecord(s)||s.sequence<=after)continue;if(n<wanted)out[n++]=s;else{more=true;break;}}if(f)f.close();const_cast<BeaconStorage*>(this)->lastError_="NONE";return true;
}
bool BeaconStorage::acknowledgeThrough(uint64_t seq){if(!healthy_){lastError_="STORAGE_NOT_READABLE";return false;}const uint64_t capped=std::min(seq,newestSequence());if(capped<=acknowledgedSequence_)return reclaimAcknowledged();const time_t now=time(nullptr);const uint64_t epochMs=now>1577836800?uint64_t(now)*1000ULL:0;if(!persistAckState(capped,epochMs))return false;acknowledgedSequence_=capped;lastAckEpochMs_=epochMs;lastAckUptimeMs_=static_cast<uint64_t>(esp_timer_get_time())/1000ULL;lastError_="NONE";return reclaimAcknowledged();}
uint64_t BeaconStorage::oldestSequence()const{return count_?nextSequence_-count_:0;}
uint64_t BeaconStorage::pendingRecords()const{if(!count_)return 0;const uint64_t first=std::max(oldestSequence(),acknowledgedSequence_+1);return first>newestSequence()?0:newestSequence()-first+1;}
