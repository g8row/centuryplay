#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-airplay-mac-logs-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT_DIR"

echo "Writing AirPlay logs to: $OUT_DIR"
echo "Start native AirPlay from this Mac now. Press Ctrl-C here after it succeeds/fails."

ifconfig > "$OUT_DIR/ifconfig.txt" 2>&1 || true
system_profiler SPAudioDataType SPDisplaysDataType SPNetworkDataType > "$OUT_DIR/system_profiler.txt" 2>&1 || true
dns-sd -L 'samsung[AirPlay]' _airplay._tcp local. > "$OUT_DIR/dns-sd-airplay-resolve.txt" 2>&1 &
RESOLVE_AIRPLAY_PID="$!"
sleep 1
kill "$RESOLVE_AIRPLAY_PID" 2>/dev/null || true
dns-sd -L '51C8E847725A@samsung[AirPlay]' _raop._tcp local. > "$OUT_DIR/dns-sd-raop-resolve.txt" 2>&1 &
RESOLVE_RAOP_PID="$!"
sleep 1
kill "$RESOLVE_RAOP_PID" 2>/dev/null || true

dns-sd -B _airplay._tcp local. > "$OUT_DIR/dns-sd-airplay-browse.txt" 2>&1 &
PIDS=("$!")
dns-sd -B _raop._tcp local. > "$OUT_DIR/dns-sd-raop-browse.txt" 2>&1 &
PIDS+=("$!")

log stream \
  --style compact \
  --info \
  --debug \
  --predicate 'process CONTAINS[c] "AirPlay" OR process CONTAINS[c] "airtunes" OR process CONTAINS[c] "mediaremote" OR process == "sharingd" OR subsystem CONTAINS[c] "AirPlay" OR subsystem CONTAINS[c] "airtunes" OR eventMessage CONTAINS[c] "AirPlay" OR eventMessage CONTAINS[c] "AirTunes" OR eventMessage CONTAINS[c] "RAOP" OR eventMessage CONTAINS[c] "FairPlay" OR eventMessage CONTAINS[c] "fp-setup" OR eventMessage CONTAINS[c] "fpaeskey" OR eventMessage CONTAINS[c] "AppleLossless"' \
  > "$OUT_DIR/unified-log-airplay.txt" 2>&1 &
PIDS+=("$!")

cleanup() {
  echo
  echo "Stopping collectors..."
  for pid in "${PIDS[@]}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
  echo "Done. Logs are in: $OUT_DIR"
}
trap cleanup INT TERM EXIT

while true; do
  sleep 1
done
