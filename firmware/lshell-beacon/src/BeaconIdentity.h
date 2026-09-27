// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <Arduino.h>

class BeaconIdentity {
 public:
  bool begin();
  const String& id() const { return id_; }
  String shortId() const;
  String hostname() const;

 private:
  String id_;
};


