// SPDX-License-Identifier: GPL-3.0-or-later
#include "BeaconIdentity.h"

#include <Preferences.h>
#include <esp_system.h>
#include <mbedtls/sha256.h>

namespace {
String hex(const uint8_t* data, size_t size) {
  static const char digits[] = "0123456789abcdef";
  String result;
  result.reserve(size * 2);
  for (size_t i = 0; i < size; ++i) {
    result += digits[data[i] >> 4];
    result += digits[data[i] & 0x0f];
  }
  return result;
}
}

bool BeaconIdentity::begin() {
  Preferences prefs;
  if (!prefs.begin("identity", false)) return false;
  id_ = prefs.getString("beacon_id", "");
  if (id_.length() == 32) {
    prefs.end();
    return true;
  }

  uint8_t material[24] = {};
  const uint64_t hardware = ESP.getEfuseMac();
  memcpy(material, &hardware, sizeof(hardware));
  for (size_t i = sizeof(hardware); i < sizeof(material); i += sizeof(uint32_t)) {
    const uint32_t randomWord = esp_random();
    memcpy(material + i, &randomWord, sizeof(randomWord));
  }
  uint8_t digest[32] = {};
  mbedtls_sha256(material, sizeof(material), digest, 0);
  id_ = hex(digest, 16);
  const bool saved = prefs.putString("beacon_id", id_) == id_.length();
  prefs.end();
  memset(material, 0, sizeof(material));
  memset(digest, 0, sizeof(digest));
  return saved;
}

String BeaconIdentity::shortId() const {
  return id_.length() >= 4 ? id_.substring(id_.length() - 4) : String("0000");
}

String BeaconIdentity::hostname() const {
  String value("lshell-beacon-");
  value += shortId();
  value.toLowerCase();
  return value;
}


