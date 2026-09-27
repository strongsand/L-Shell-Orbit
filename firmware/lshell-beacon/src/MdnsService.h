// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include "BeaconIdentity.h"

class MdnsService {
 public:
  bool begin(const BeaconIdentity& identity, bool setupActive);
  void updateTxt(bool paired, bool setupActive);
  void end();
  bool active() const { return active_; }

 private:
  bool active_ = false;
};


