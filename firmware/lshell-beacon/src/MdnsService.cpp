// SPDX-License-Identifier: GPL-3.0-or-later
#include "MdnsService.h"

#include <ESPmDNS.h>
#include "BeaconConfig.h"

bool MdnsService::begin(const BeaconIdentity& identity, bool setupActive) {
  end();
  if (!MDNS.begin(identity.hostname().c_str())) return false;
  MDNS.addService(BeaconConfig::kMdnsService, BeaconConfig::kMdnsProtocol, BeaconConfig::kHttpPort);
  MDNS.addServiceTxt(BeaconConfig::kMdnsService, BeaconConfig::kMdnsProtocol, "id", identity.id());
  MDNS.addServiceTxt(BeaconConfig::kMdnsService, BeaconConfig::kMdnsProtocol, "fw", BeaconConfig::kFirmwareVersion);
  MDNS.addServiceTxt(BeaconConfig::kMdnsService, BeaconConfig::kMdnsProtocol, "proto", String(BeaconConfig::kProtocolVersion));
  active_ = true;
  updateTxt(false, setupActive);
  return true;
}

void MdnsService::updateTxt(bool paired, bool setupActive) {
  if (!active_) return;
  MDNS.addServiceTxt(BeaconConfig::kMdnsService, BeaconConfig::kMdnsProtocol, "paired", paired ? "1" : "0");
  MDNS.addServiceTxt(BeaconConfig::kMdnsService, BeaconConfig::kMdnsProtocol, "setup", setupActive ? "1" : "0");
}

void MdnsService::end() {
  if (active_) MDNS.end();
  active_ = false;
}

