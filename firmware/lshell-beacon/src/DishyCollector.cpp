// SPDX-License-Identifier: GPL-3.0-or-later
#include "DishyCollector.h"

#include <WiFi.h>
#include <nghttp2/nghttp2.h>
#include <esp_timer.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/task.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <ctime>
#include <memory>
#include <new>
#include <utility>
#include <vector>

namespace {
const IPAddress kDishAddress(192,168,100,1); constexpr uint16_t kDishPort=9200;
constexpr uint32_t kProbeIntervalMs=60000, kHistoryIntervalMs=300000, kRpcTimeoutMs=8000;
constexpr uint64_t kSampleStride=5;
constexpr size_t kMaxGrpcMessageBytes=512*1024, kMaxRetainedPerMetric=2048;
// The combined libnghttp2/lwIP call chain overflowed reproducibly at 12 and 16 KiB
// on ESP32-D0WD-V3. With 138 KiB observed free at 16 KiB, 20 KiB remains conservative.
constexpr uint32_t kGrpcTaskStackBytes=20*1024;

#define GRPC_NV(NAME,VALUE) {reinterpret_cast<uint8_t*>(const_cast<char*>(NAME)),reinterpret_cast<uint8_t*>(const_cast<char*>(VALUE)),sizeof(NAME)-1,sizeof(VALUE)-1,NGHTTP2_NV_FLAG_NONE}
const nghttp2_nv kGrpcHeaders[]={
  GRPC_NV(":method","POST"),
  GRPC_NV(":scheme","http"),
  GRPC_NV(":path","/SpaceX.API.Device.Device/Handle"),
  GRPC_NV(":authority","192.168.100.1:9200"),
  GRPC_NV("content-type","application/grpc"),
  GRPC_NV("te","trailers")
};
#undef GRPC_NV

enum class StreamError : uint8_t { None, Envelope, History };

struct MetricSeries {
  size_t count=0,regularCount=0,incomingIndex=0;uint64_t first=0,base=0,last=0;
  bool hasLastExtra=false;uint8_t floatBytes[4]{};uint8_t floatUsed=0;std::vector<float> values;

  bool begin(size_t bytes,uint64_t current){
    if(!current||bytes%sizeof(float)!=0)return false;count=bytes/sizeof(float);if(!count)return true;
    first=current>count?current-count:0;last=current-1;base=first;const uint64_t remainder=base%kSampleStride;if(remainder)base+=kSampleStride-remainder;
    regularCount=base<current?static_cast<size_t>((last-base)/kSampleStride+1):0;
    hasLastExtra=last<base||((last-base)%kSampleStride)!=0;const size_t slots=regularCount+(hasLastExtra?1:0);
    if(slots>kMaxRetainedPerMetric)return false;values.assign(slots,NAN);return true;
  }

  bool feed(const uint8_t* data,size_t length){
    while(length){const size_t take=std::min<size_t>(sizeof(float)-floatUsed,length);memcpy(floatBytes+floatUsed,data,take);floatUsed+=take;data+=take;length-=take;
      if(floatUsed==sizeof(float)){if(incomingIndex>=count)return false;float value=NAN;memcpy(&value,floatBytes,sizeof(value));
        const size_t firstIndex=static_cast<size_t>(first%count);const uint64_t counter=first+((incomingIndex+count-firstIndex)%count);
        if(counter<=last){size_t slot=0;bool keep=false;if(counter>=base&&(counter-base)%kSampleStride==0){slot=static_cast<size_t>((counter-base)/kSampleStride);keep=slot<regularCount;}
          else if(hasLastExtra&&counter==last){slot=regularCount;keep=true;}if(keep)values[slot]=value;}
        ++incomingIndex;floatUsed=0;}}
    return true;
  }

  bool complete() const{return incomingIndex==count&&floatUsed==0;}
  float value(uint64_t counter) const{if(!count||counter<first||counter>last)return NAN;if(counter>=base&&(counter-base)%kSampleStride==0){const size_t slot=static_cast<size_t>((counter-base)/kSampleStride);return slot<regularCount?values[slot]:NAN;}return hasLastExtra&&counter==last?values[regularCount]:NAN;}
  size_t memoryBytes() const{return values.capacity()*sizeof(float);}
};

struct ParsedHistory {
  uint64_t current=0;MetricSeries fields[6];
  size_t maxCount() const{size_t result=0;for(const MetricSeries& field:fields)result=std::max(result,field.count);return result;}
  size_t memoryBytes() const{size_t result=0;for(const MetricSeries& field:fields)result+=field.memoryBytes();return result;}
};

struct FetchResult {ParsedHistory history;StreamError parseError=StreamError::None;bool ok=false;};

class HistoryStreamParser {
 public:
  bool feed(const uint8_t* data,size_t length){
    while(length&&ok_){
      if(state_==State::Packed){const size_t take=std::min(length,remaining_);if(!history_.fields[slot_].feed(data,take)){ok_=false;break;}data+=take;length-=take;remaining_-=take;if(!remaining_){if(!history_.fields[slot_].complete())ok_=false;state_=State::Tag;}continue;}
      if(state_==State::Skip){const size_t take=std::min(length,remaining_);data+=take;length-=take;remaining_-=take;if(!remaining_)state_=State::Tag;continue;}
      const uint8_t byte=*data++;--length;if(!pushVarint(byte))continue;const uint64_t value=varint_;resetVarint();
      if(state_==State::Tag){field_=static_cast<uint32_t>(value>>3);wire_=static_cast<uint8_t>(value&7);if(!field_){ok_=false;break;}if(wire_==0)state_=State::Scalar;else if(wire_==1){remaining_=8;state_=State::Skip;}else if(wire_==2)state_=State::Length;else if(wire_==5){remaining_=4;state_=State::Skip;}else ok_=false;}
      else if(state_==State::Scalar){if(field_==1){history_.current=value;currentSeen_=true;}state_=State::Tag;}
      else if(state_==State::Length){const int slot=metricSlot(field_);if(slot>=0){if(!currentSeen_||value>SIZE_MAX||!history_.fields[slot].begin(static_cast<size_t>(value),history_.current)){ok_=false;break;}slot_=slot;remaining_=static_cast<size_t>(value);state_=remaining_?State::Packed:State::Tag;}
        else{if(value>SIZE_MAX){ok_=false;break;}remaining_=static_cast<size_t>(value);state_=remaining_?State::Skip:State::Tag;}}
    }return ok_;
  }
  bool finish() const{return ok_&&currentSeen_&&state_==State::Tag&&shift_==0;}
  size_t memoryBytes() const{return history_.memoryBytes();}
  ParsedHistory&& take(){return std::move(history_);}
 private:
  enum class State:uint8_t{Tag,Scalar,Length,Skip,Packed};State state_=State::Tag;ParsedHistory history_{};
  uint64_t varint_=0;uint8_t shift_=0,wire_=0;uint32_t field_=0;size_t remaining_=0;int slot_=0;bool ok_=true,currentSeen_=false;
  bool pushVarint(uint8_t byte){if(shift_>=64)return ok_=false;varint_|=uint64_t(byte&0x7f)<<shift_;if(!(byte&0x80))return true;shift_+=7;return false;}
  void resetVarint(){varint_=0;shift_=0;}
  static int metricSlot(uint32_t field){return field==1001?0:field==1002?1:field==1003?2:field==1004?3:field==1005?4:field==1010?5:-1;}
};

class GrpcResponseStream {
 public:
  bool feed(const uint8_t* data,size_t length){bodySize_+=length;while(length&&error_==StreamError::None){
      if(headerUsed_<sizeof(header_)){const size_t take=std::min(length,sizeof(header_)-headerUsed_);memcpy(header_+headerUsed_,data,take);headerUsed_+=take;data+=take;length-=take;
        if(headerUsed_==sizeof(header_)){if(header_[0]!=0){error_=StreamError::Envelope;break;}messageRemaining_=(uint32_t(header_[1])<<24)|(uint32_t(header_[2])<<16)|(uint32_t(header_[3])<<8)|header_[4];if(!messageRemaining_||messageRemaining_>kMaxGrpcMessageBytes){error_=StreamError::Envelope;break;}}continue;}
      if(!messageRemaining_){error_=StreamError::Envelope;break;}const size_t take=std::min<size_t>(length,messageRemaining_);if(!feedOuter(data,take))break;data+=take;length-=take;messageRemaining_-=take;
    }return error_==StreamError::None;}
  bool finish(){if(error_!=StreamError::None||headerUsed_!=sizeof(header_)||messageRemaining_||!historyFound_||state_!=State::Tag||shift_!=0){if(error_==StreamError::None)error_=StreamError::Envelope;return false;}if(!history_.finish()){error_=StreamError::History;return false;}return true;}
  StreamError error() const{return error_;}size_t bodySize() const{return bodySize_;}size_t memoryBytes() const{return history_.memoryBytes();}ParsedHistory&& take(){return history_.take();}
 private:
  enum class State:uint8_t{Tag,Scalar,Length,Skip,History};State state_=State::Tag;HistoryStreamParser history_{};StreamError error_=StreamError::None;
  uint8_t header_[5]{};size_t headerUsed_=0,bodySize_=0,remaining_=0;uint32_t messageRemaining_=0,field_=0;uint64_t varint_=0;uint8_t shift_=0,wire_=0;bool historyFound_=false;
  bool feedOuter(const uint8_t* data,size_t length){while(length&&error_==StreamError::None){
      if(state_==State::History){const size_t take=std::min(length,remaining_);if(!history_.feed(data,take)){error_=StreamError::History;break;}data+=take;length-=take;remaining_-=take;if(!remaining_){historyFound_=true;state_=State::Tag;}continue;}
      if(state_==State::Skip){const size_t take=std::min(length,remaining_);data+=take;length-=take;remaining_-=take;if(!remaining_)state_=State::Tag;continue;}
      const uint8_t byte=*data++;--length;if(!pushVarint(byte))continue;const uint64_t value=varint_;resetVarint();
      if(state_==State::Tag){field_=static_cast<uint32_t>(value>>3);wire_=static_cast<uint8_t>(value&7);if(!field_){error_=StreamError::Envelope;break;}if(wire_==0)state_=State::Scalar;else if(wire_==1){remaining_=8;state_=State::Skip;}else if(wire_==2)state_=State::Length;else if(wire_==5){remaining_=4;state_=State::Skip;}else error_=StreamError::Envelope;}
      else if(state_==State::Scalar)state_=State::Tag;
      else if(state_==State::Length){if(value>SIZE_MAX){error_=StreamError::Envelope;break;}remaining_=static_cast<size_t>(value);if(field_==2006){if(historyFound_){error_=StreamError::Envelope;break;}state_=remaining_?State::History:State::Tag;}else state_=remaining_?State::Skip:State::Tag;}
    }return error_==StreamError::None;}
  bool pushVarint(uint8_t byte){if(shift_>=64){error_=StreamError::Envelope;return false;}varint_|=uint64_t(byte&0x7f)<<shift_;if(!(byte&0x80))return true;shift_+=7;return false;}
  void resetVarint(){varint_=0;shift_=0;}
};

struct RpcContext {WiFiClient client;GrpcResponseStream stream;const uint8_t* request=nullptr;size_t requestLength=0,requestOffset=0;bool closed=false;
  int httpStatus=0,grpcStatus=-1,sendError=0,recvError=0;uint32_t streamError=0,deadline=0;};

ssize_t sendCallback(nghttp2_session*,const uint8_t* data,size_t length,int,void* user){auto& c=*static_cast<RpcContext*>(user);const size_t n=c.client.write(data,length);return n?static_cast<ssize_t>(n):NGHTTP2_ERR_WOULDBLOCK;}
ssize_t recvCallback(nghttp2_session*,uint8_t* data,size_t length,int,void* user){auto& c=*static_cast<RpcContext*>(user);const int available=c.client.available();if(available<=0)return c.client.connected()?NGHTTP2_ERR_WOULDBLOCK:NGHTTP2_ERR_EOF;return c.client.read(data,std::min<size_t>(length,available));}
ssize_t requestRead(nghttp2_session*,int32_t,uint8_t* buffer,size_t length,uint32_t* flags,nghttp2_data_source* source,void*){auto& c=*static_cast<RpcContext*>(source->ptr);if(c.requestOffset>=c.requestLength){*flags|=NGHTTP2_DATA_FLAG_EOF;return 0;}const size_t remaining=c.requestLength-c.requestOffset;const size_t n=std::min(length,remaining);memcpy(buffer,c.request+c.requestOffset,n);c.requestOffset+=n;if(c.requestOffset==c.requestLength)*flags|=NGHTTP2_DATA_FLAG_EOF;return n;}
int dataChunk(nghttp2_session*,uint8_t,int32_t,const uint8_t* data,size_t length,void* user){auto& c=*static_cast<RpcContext*>(user);return c.stream.feed(data,length)?0:NGHTTP2_ERR_TEMPORAL_CALLBACK_FAILURE;}
int streamClose(nghttp2_session*,int32_t,uint32_t errorCode,void* user){auto& c=*static_cast<RpcContext*>(user);c.closed=true;c.streamError=errorCode;return 0;}
bool headerNameEquals(const uint8_t* name,size_t nameLen,const char* expected,size_t expectedLen){return nameLen==expectedLen&&memcmp(name,expected,expectedLen)==0;}
int parseHeaderInteger(const uint8_t* value,size_t valueLen){if(!valueLen)return -1;int result=0;for(size_t i=0;i<valueLen;++i){if(value[i]<'0'||value[i]>'9')return -1;result=result*10+(value[i]-'0');}return result;}
int headerCallback(nghttp2_session*,const nghttp2_frame*,const uint8_t* name,size_t nameLen,const uint8_t* value,size_t valueLen,uint8_t,void* user){auto& c=*static_cast<RpcContext*>(user);if(headerNameEquals(name,nameLen,":status",7))c.httpStatus=parseHeaderInteger(value,valueLen);else if(headerNameEquals(name,nameLen,"grpc-status",11))c.grpcStatus=parseHeaderInteger(value,valueLen);return 0;}

bool grpcHistory(ParsedHistory& response,StreamError& parseError){
  // gRPC frame: compression=0, protobuf length=3, Request.get_history (field 1007) = {}.
  static const uint8_t request[]={0,0,0,0,3,0xFA,0x3E,0};std::unique_ptr<RpcContext> context(new(std::nothrow) RpcContext());if(!context){Serial.println(F("GRPC_CONTEXT_ALLOC_FAIL"));return false;}RpcContext& c=*context;c.request=request;c.requestLength=sizeof(request);c.deadline=millis()+kRpcTimeoutMs;
  Serial.print(F("GRPC_HEAP_FREE="));Serial.println(ESP.getFreeHeap());Serial.print(F("GRPC_LARGEST_BLOCK="));Serial.println(ESP.getMaxAllocHeap());
  if(!c.client.connect(kDishAddress,kDishPort,2500)){Serial.println(F("GRPC_TCP_CONNECT_FAIL"));return false;}Serial.println(F("GRPC_TCP_CONNECT_OK"));
  Serial.print(F("GRPC_STACK_AFTER_TCP="));Serial.println(uxTaskGetStackHighWaterMark(nullptr));
  Serial.println(F("GRPC_STAGE=CALLBACKS_CREATE"));
  nghttp2_session_callbacks* callbacks=nullptr;nghttp2_session* session=nullptr;if(nghttp2_session_callbacks_new(&callbacks)!=0||!callbacks){Serial.println(F("GRPC_SESSION_CREATE_FAIL"));c.client.stop();return false;}
  nghttp2_session_callbacks_set_send_callback(callbacks,sendCallback);nghttp2_session_callbacks_set_recv_callback(callbacks,recvCallback);
  nghttp2_session_callbacks_set_on_data_chunk_recv_callback(callbacks,dataChunk);nghttp2_session_callbacks_set_on_stream_close_callback(callbacks,streamClose);nghttp2_session_callbacks_set_on_header_callback(callbacks,headerCallback);
  Serial.println(F("GRPC_STAGE=SESSION_CREATE"));
  if(nghttp2_session_client_new(&session,callbacks,&c)!=0){Serial.println(F("GRPC_SESSION_CREATE_FAIL"));nghttp2_session_callbacks_del(callbacks);c.client.stop();return false;}
  Serial.print(F("GRPC_STACK_AFTER_SESSION_CREATE="));Serial.println(uxTaskGetStackHighWaterMark(nullptr));
  nghttp2_settings_entry setting{NGHTTP2_SETTINGS_MAX_CONCURRENT_STREAMS,1};if(nghttp2_submit_settings(session,NGHTTP2_FLAG_NONE,&setting,1)<0){Serial.println(F("GRPC_SUBMIT_FAIL"));nghttp2_session_del(session);nghttp2_session_callbacks_del(callbacks);c.client.stop();return false;}
  nghttp2_data_provider provider{};provider.source.ptr=&c;provider.read_callback=requestRead;const int32_t stream=nghttp2_submit_request(session,nullptr,kGrpcHeaders,sizeof(kGrpcHeaders)/sizeof(kGrpcHeaders[0]),&provider,&c);
  bool ok=stream>0;if(!ok)Serial.println(F("GRPC_SUBMIT_FAIL"));
  Serial.print(F("GRPC_STACK_BEFORE_IO="));Serial.println(uxTaskGetStackHighWaterMark(nullptr));
  while(ok&&!c.closed&&static_cast<int32_t>(millis()-c.deadline)<0){const int sent=nghttp2_session_send(session);if(sent<0){c.sendError=sent;Serial.println(F("GRPC_SEND_FAIL"));ok=false;break;}const int received=nghttp2_session_recv(session);if(received<0&&received!=NGHTTP2_ERR_WOULDBLOCK){c.recvError=received;Serial.println(F("GRPC_RECV_FAIL"));ok=false;break;}delay(2);}
  if(ok&&!c.closed&&static_cast<int32_t>(millis()-c.deadline)>=0){Serial.println(F("GRPC_TIMEOUT"));ok=false;}if(ok&&c.closed&&!c.stream.finish())ok=false;parseError=c.stream.error();
  Serial.print(F("GRPC_HTTP_STATUS="));Serial.println(c.httpStatus);Serial.print(F("GRPC_STATUS="));Serial.println(c.grpcStatus);Serial.print(F("GRPC_BODY_SIZE="));Serial.println(c.stream.bodySize());Serial.print(F("GRPC_STREAM_CLOSED="));Serial.println(c.closed?1:0);
  if(c.streamError!=NGHTTP2_NO_ERROR){Serial.print(F("GRPC_STREAM_ERROR="));Serial.println(c.streamError);}if(c.sendError){Serial.print(F("GRPC_SEND_CODE="));Serial.println(c.sendError);}if(c.recvError){Serial.print(F("GRPC_RECV_CODE="));Serial.println(c.recvError);}
  ok=ok&&c.closed&&c.streamError==NGHTTP2_NO_ERROR&&c.httpStatus==200&&c.grpcStatus==0;
  const size_t retainedBytes=c.stream.memoryBytes();if(ok)response=c.stream.take();Serial.print(F("GRPC_BUFFER_REQUIRED="));Serial.println(retainedBytes);
  nghttp2_session_del(session);nghttp2_session_callbacks_del(callbacks);c.client.stop();Serial.print(F("GRPC_HEAP_AFTER="));Serial.println(ESP.getFreeHeap());return ok;
}
}

bool DishyCollector::begin(){nextProbeAt_=millis();nextHistoryAt_=millis();fetchResultQueue_=xQueueCreate(1,sizeof(FetchResult*));return storage_.healthy()&&fetchResultQueue_;}
bool DishyCollector::probe(){Serial.println(F("DISH_CONNECT_ATTEMPT"));WiFiClient c;const bool ok=c.connect(kDishAddress,kDishPort,1500);c.stop();return ok;}

void DishyCollector::update(uint32_t now){
  consumeFetchResult(now);
  if(static_cast<int32_t>(now-nextProbeAt_)>=0){nextProbeAt_=now+kProbeIntervalMs;const bool known=reachableKnown_;const bool was=reachable_;reachable_=probe();reachableKnown_=true;
    if(reachable_&&(!known||!was)){Serial.println(F("DISH_AVAILABLE"));nextHistoryAt_=now;}else if(!reachable_&&(!known||was))Serial.println(F("DISH_UNAVAILABLE"));}
  if(reachable_&&!fetchRunning_&&static_cast<int32_t>(now-nextHistoryAt_)>=0){nextHistoryAt_=now+kHistoryIntervalMs;Serial.println(F("HISTORY_FETCH_START"));if(!startFetchTask()){Serial.println(F("HISTORY_FETCH_ERROR_GRPC"));lastFetchOk_=false;nextHistoryAt_=now+30000;}}
}

bool DishyCollector::startFetchTask(){
  if(fetchRunning_||!fetchResultQueue_)return false;fetchRunning_=true;
  Serial.println(F("DISH_GRPC_TASK_START"));
  Serial.print(F("GRPC_STACK_CREATED_SIZE="));Serial.println(kGrpcTaskStackBytes);
  const BaseType_t created=xTaskCreatePinnedToCore(fetchTaskEntry,"dishGrpc",kGrpcTaskStackBytes,this,1,nullptr,1);
  if(created!=pdPASS){fetchRunning_=false;Serial.println(F("GRPC_TASK_CREATE_FAIL"));return false;}Serial.println(F("DISH_GRPC_TASK_CREATED"));return true;
}

void DishyCollector::fetchTaskEntry(void* context){
  DishyCollector* owner=static_cast<DishyCollector*>(context);Serial.print(F("GRPC_STACK_BEFORE="));Serial.println(uxTaskGetStackHighWaterMark(nullptr));
  FetchResult* result=new(std::nothrow) FetchResult();if(result)result->ok=grpcHistory(result->history,result->parseError);else Serial.println(F("GRPC_RESULT_ALLOC_FAIL"));
  const UBaseType_t minimum=uxTaskGetStackHighWaterMark(nullptr);Serial.print(F("GRPC_STACK_MIN="));Serial.println(minimum);Serial.print(F("GRPC_STACK_AFTER="));Serial.println(uxTaskGetStackHighWaterMark(nullptr));
  xQueueSend(static_cast<QueueHandle_t>(owner->fetchResultQueue_),&result,portMAX_DELAY);
  vTaskDelete(nullptr);
}

void DishyCollector::consumeFetchResult(uint32_t now){
  if(!fetchRunning_||!fetchResultQueue_)return;FetchResult* result=nullptr;if(xQueueReceive(static_cast<QueueHandle_t>(fetchResultQueue_),&result,0)!=pdTRUE)return;fetchRunning_=false;
  if(!result){Serial.println(F("HISTORY_FETCH_ERROR_GRPC"));lastFetchOk_=false;nextHistoryAt_=now+30000;return;}
  if(!result->ok){if(result->parseError==StreamError::Envelope)Serial.println(F("HISTORY_FETCH_ERROR_MESSAGE"));else if(result->parseError==StreamError::History)Serial.println(F("HISTORY_FETCH_ERROR_PARSE"));else Serial.println(F("HISTORY_FETCH_ERROR_GRPC"));lastFetchOk_=false;}
  else lastFetchOk_=storeHistory(&result->history);delete result;if(!lastFetchOk_)nextHistoryAt_=now+30000;
}

bool DishyCollector::storeHistory(void* parsedHistory){
  ParsedHistory& history=*static_cast<ParsedHistory*>(parsedHistory);
  const size_t count=history.maxCount();if(!count){Serial.println(F("HISTORY_FETCH_ERROR_EMPTY"));return false;}const uint64_t current=history.current;
  const uint64_t availableFirst=current>count?current-count:0;const uint64_t saved=storage_.lastDishyCounter();uint64_t first=(saved&&current>saved)?std::max<uint64_t>(availableFirst,saved+1):availableFirst;size_t added=0;
  const uint64_t collectionUptime=static_cast<uint64_t>(esp_timer_get_time())/1000ULL;const time_t epoch=time(nullptr);const bool validTime=epoch>1577836800;
  for(uint64_t counter=first;counter<current;++counter){if(counter%kSampleStride!=0&&counter+1!=current)continue;BeaconStoredSample s{};s.dishyCounter=counter;
    const float dropFraction=history.fields[0].value(counter);s.dropRate=dropFraction*100.f;s.latencyMs=history.fields[1].value(counter);s.downloadMbps=history.fields[2].value(counter)/1000000.f;s.uploadMbps=history.fields[3].value(counter)/1000000.f;
    if(isfinite(s.dropRate))s.flags|=METRIC_DROP;if(isfinite(s.latencyMs))s.flags|=METRIC_LATENCY;if(isfinite(s.downloadMbps))s.flags|=METRIC_DOWNLOAD;if(isfinite(s.uploadMbps))s.flags|=METRIC_UPLOAD;
    s.signal=history.fields[4].value(counter);if(isfinite(s.signal))s.flags|=METRIC_SIGNAL;s.powerWatts=history.fields[5].value(counter);if(isfinite(s.powerWatts)&&s.powerWatts>0)s.flags|=METRIC_POWER;s.connectivity=isfinite(s.dropRate)?(s.dropRate<100.f?1:0):2;
    const uint64_t ageMs=(current-1-counter)*1000ULL;if(validTime){s.timestampMs=static_cast<int64_t>((uint64_t(epoch)-(current-1-counter))*1000ULL);s.flags|=METRIC_TIMESTAMP;}else s.timestampMs=static_cast<int64_t>(collectionUptime)-static_cast<int64_t>(ageMs);
    if(storage_.append(s)){++added;if(counter+1==current)lastSampleUptimeMs_=collectionUptime;if(validTime)lastSampleEpochMs_=static_cast<uint64_t>(s.timestampMs);}else{Serial.println(F("HISTORY_FETCH_ERROR_STORAGE"));return false;}}
  if(!storage_.flush()){Serial.println(F("HISTORY_FETCH_ERROR_FLUSH"));return false;}lastCollectionUptimeMs_=collectionUptime;lastCollectionEpochMs_=validTime?uint64_t(epoch)*1000ULL:0;Serial.println(F("HISTORY_FETCH_OK"));Serial.print(F("NEW_RECORDS="));Serial.println(added);
  Serial.print(F("STORAGE_RANGE="));Serial.print(storage_.oldestSequence());Serial.print(F(".."));Serial.println(storage_.newestSequence());return true;
}
