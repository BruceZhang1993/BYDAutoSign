#!/usr/bin/env bash
set -euo pipefail

# Binaries are distributed by byd_sign under MIT; pin both revision and blob SHA.
# The downloaded binaries are packed into the APK; users do NOT download another module.
UPSTREAM=b9001bb2f3793322e0fdf7923f5969ace33e79ca
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/runtime/bin"
mkdir -p "$DEST"

verify_blob() {
  local file="$1" expected="$2" size actual
  size=$(wc -c < "$file" | tr -d ' ')
  actual=$( { printf 'blob %s\0' "$size"; cat "$file"; } | sha1sum | cut -d' ' -f1 )
  [[ "$actual" == "$expected" ]] || { echo "Git blob SHA mismatch: $file" >&2; exit 1; }
}
download() {
  local abi="$1" name="$2" sha="$3"
  local target="$DEST/$abi/$name"
  mkdir -p "$DEST/$abi"
  if [[ ! -f "$target" ]]; then
    curl --fail --location --retry 3 --connect-timeout 20 --output "$target" "https://raw.githubusercontent.com/xiaojinwen/byd_sign/$UPSTREAM/bin/$abi/$name"
  fi
  verify_blob "$target" "$sha"
  chmod 755 "$target"
}

download arm64-v8a curl a829fce33840ac50fa75b5348795bd1f8fcee2f1
download arm64-v8a openssl 3b394b819f025ee521307d1a3943a527651eecd1
download armeabi-v7a curl 663a05693e13d63a81c6648841c32ba1df13f27f
download armeabi-v7a openssl 00070376d57e1dda4ba7c0c2da2a556ab9b8cdcc
echo "Pinned byd_sign native runtime verified."
