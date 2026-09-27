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
constexpr size_t kReserveBytes=48*1024; }

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
  const bool metadataLoaded=loadMetadata();
  healthy_=rebuildFromRecords();
  if(!metadataLoaded)acknowledgedSequence_=0;
  // Uptime is meaningful only within the current boot. The ACK sequence remains durable.
  lastAckUptimeMs_=0;
  if(healthy_)healthy_=persistMetadata(); return healthy_;
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
  Metadata m{kMetaMagic,kMetaVersion,sizeof(Metadata),++generation_,capacity_,head_,count_,nextSequence_,acknowledgedSequence_,lastDishyCounter_,lastAckEpochMs_,lastAckUptimeMs_,0};
  m.crc=crc32(reinterpret_cast<const uint8_t*>(&m),offsetof(Metadata,crc));const char* path=(generation_&1)?kMetaPath1:kMetaPath0;
  File f=LittleFS.open(path,FILE_WRITE);if(!f)return false;const bool ok=f.write(reinterpret_cast<const uint8_t*>(&m),sizeof(m))==sizeof(m);f.flush();f.close();if(ok)uncommitted_=0;return ok;
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
  if(!healthy_||!capacity_)return false;s.magic=0x4C534831;s.version=1;s.size=sizeof(s);s.sequence=nextSequence_;s.bootId=currentBootId_;s.crc=0;s.crc=crc32(reinterpret_cast<const uint8_t*>(&s),offsetof(BeaconStoredSample,crc));
  File f=LittleFS.open(kDataPath,LittleFS.exists(kDataPath)?"r+":FILE_WRITE);if(!f||!f.seek(static_cast<size_t>(head_)*sizeof(s),SeekSet)||f.write(reinterpret_cast<const uint8_t*>(&s),sizeof(s))!=sizeof(s)){if(f)f.close();healthy_=false;return false;}
  f.flush();f.close();head_=(head_+1)%capacity_;if(count_<capacity_)++count_;++nextSequence_;lastDishyCounter_=s.dishyCounter;
  if(++uncommitted_>=16)healthy_=persistMetadata();return healthy_;
}

bool BeaconStorage::flush(){if(!healthy_||!uncommitted_)return healthy_;healthy_=persistMetadata();return healthy_;}

uint32_t BeaconStorage::slotForOldestOffset(uint32_t offset)const{const uint32_t oldest=(head_+capacity_-count_)%capacity_;return(oldest+offset)%capacity_;}
bool BeaconStorage::readSlot(uint32_t slot,BeaconStoredSample& s)const{File f=LittleFS.open(kDataPath,FILE_READ);if(!f||!f.seek(static_cast<size_t>(slot)*sizeof(s),SeekSet))return false;const bool ok=f.read(reinterpret_cast<uint8_t*>(&s),sizeof(s))==sizeof(s);f.close();return ok;}
bool BeaconStorage::validRecord(const BeaconStoredSample& s){return s.magic==0x4C534831&&s.version==1&&s.size==sizeof(s)&&s.crc==crc32(reinterpret_cast<const uint8_t*>(&s),offsetof(BeaconStoredSample,crc));}

bool BeaconStorage::readAfter(uint64_t after,size_t limit,BeaconStoredSample* out,size_t cap,size_t& n,bool& more)const{
  n=0;more=false;if(!healthy_||!out||!cap)return false;const size_t wanted=std::min<size_t>(std::min<size_t>(limit,cap),500);File f=LittleFS.open(kDataPath,FILE_READ);if(!f&&count_)return false;
  for(uint32_t i=0;i<count_;++i){BeaconStoredSample s{};const uint32_t slot=slotForOldestOffset(i);if(!f.seek(static_cast<size_t>(slot)*sizeof(s),SeekSet)||f.read(reinterpret_cast<uint8_t*>(&s),sizeof(s))!=sizeof(s)||!validRecord(s)||s.sequence<=after)continue;if(n<wanted)out[n++]=s;else{more=true;break;}}if(f)f.close();return true;
}
bool BeaconStorage::acknowledgeThrough(uint64_t seq){if(!healthy_)return false;const uint64_t capped=std::min(seq,newestSequence());if(capped<=acknowledgedSequence_)return true;acknowledgedSequence_=capped;const time_t now=time(nullptr);lastAckEpochMs_=now>1577836800?uint64_t(now)*1000ULL:0;lastAckUptimeMs_=static_cast<uint64_t>(esp_timer_get_time())/1000ULL;return persistMetadata();}
uint64_t BeaconStorage::oldestSequence()const{return count_?nextSequence_-count_:0;}
uint64_t BeaconStorage::pendingRecords()const{if(!count_)return 0;const uint64_t first=std::max(oldestSequence(),acknowledgedSequence_+1);return first>newestSequence()?0:newestSequence()-first+1;}
