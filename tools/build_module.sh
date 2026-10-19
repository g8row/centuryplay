#!/bin/bash
# Package the centuryplay root module (Magisk / KernelSU / APatch) around a built APK.
# Usage: tools/build_module.sh <centuryplay.apk> [out.zip]
set -euo pipefail
cd "$(dirname "$0")/.."
apk=$(realpath "${1:?usage: build_module.sh <apk> [out.zip]}")
name=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
out=$(realpath -m "${2:-build/centuryplay-module-v$name.zip}")

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
cp -r module/. "$stage/"
rm -f "$stage/update.json"
sed -i "s/@VERSION@/v$name/; s/@VERSION_CODE@/$code/" "$stage/module.prop"
mkdir -p "$stage/system/priv-app/CenturyPlay"
cp "$apk" "$stage/system/priv-app/CenturyPlay/CenturyPlay.apk"
mkdir -p "$(dirname "$out")"
rm -f "$out"
(cd "$stage" && zip -qr9 "$out" .)
echo "$out"
