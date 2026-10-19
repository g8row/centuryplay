#!/bin/bash
# Build a signed release: build/centuryplay-v<version>.apk and the root module around it,
# build/centuryplay-module-v<version>.zip. Sign with the same key as earlier releases, or
# they won't install over each other (and the module's /data copy can't update the app).
#
# Usage: tools/release.sh <keystore> [key alias]
#   passwords: $KS_PASS / $KEY_PASS (apksigner prompts when unset)
#   build tools: $ANDROID_HOME (or sdk.dir in local.properties)
set -euo pipefail
cd "$(dirname "$0")/.."

keystore=$(realpath "${1:?usage: tools/release.sh <keystore> [key alias]}")
alias=${2:-}

sdk=${ANDROID_HOME:-$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null)}
[[ -d $sdk/build-tools ]] || { echo "no Android SDK: set ANDROID_HOME" >&2; exit 1; }
bt=$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)

name=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)

./gradlew --no-daemon -q assembleRelease

apk=build/centuryplay-v$name.apk
mkdir -p build
"$bt/zipalign" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk "$apk"

sign=(sign --ks "$keystore")
[[ -n $alias ]] && sign+=(--ks-key-alias "$alias")
[[ -n ${KS_PASS:-} ]] && sign+=(--ks-pass env:KS_PASS)
[[ -n ${KEY_PASS:-} ]] && sign+=(--key-pass env:KEY_PASS)
"$bt/apksigner" "${sign[@]}" "$apk"
"$bt/apksigner" verify --print-certs "$apk" | head -3

tools/build_module.sh "$apk" "build/centuryplay-module-v$name.zip"
echo "$apk"
