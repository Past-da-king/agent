#!/usr/bin/env bash
# Is this APK signed with the key the app has always shipped with? Run it on every build before it is uploaded.
#
#   tools/check-apk-signature.sh app/build/outputs/apk/release/app-release.apk
#
# It compares the certificate's SHA-256 fingerprint with the published one. Anything else means the file cannot install
# over an existing copy of the app (INSTALL_FAILED_UPDATE_INCOMPATIBLE) and must never be released.
set -eu
APK="${1:?usage: tools/check-apk-signature.sh <apk>}"
EXPECTED=38d9d3377b0ae2229a0803a1ff84eb7a7d35c2db80334772c4b8072f27b0959a

APKSIGNER="${APKSIGNER:-}"
if [ -z "$APKSIGNER" ]; then
  for candidate in "${ANDROID_HOME:-}/build-tools"/*/apksigner "$HOME/Android/Sdk/build-tools"/*/apksigner; do
    [ -x "$candidate" ] && APKSIGNER="$candidate"
  done
fi
[ -n "$APKSIGNER" ] || { echo "apksigner not found: set APKSIGNER or ANDROID_HOME"; exit 2; }

GOT=$("$APKSIGNER" verify --print-certs "$APK" | grep -m1 'SHA-256 digest' | sed 's/.*SHA-256 digest: *//' | tr -d ': ' | tr 'A-F' 'a-f')
if [ "$GOT" != "$EXPECTED" ]; then
  echo "REFUSED: $APK is signed with $GOT"
  echo "expected                $EXPECTED"
  echo "This file cannot install over an existing copy. Sign it with the published key and check again."
  exit 1
fi
echo "ok: $APK is signed with the published key"
