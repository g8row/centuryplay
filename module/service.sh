# Runs late in boot (POSIX sh: busybox ash under Magisk / KernelSU).
MODDIR=${0%/*}
PKG=com.airplay.streamer
APK=$MODDIR/system/priv-app/CenturyPlay/CenturyPlay.apk
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done

# The app must run from /data (an update of the system copy): module files are hidden from
# app processes by most root solutions. Recreate the update if it's gone ("uninstall updates").
case "$(pm path "$PKG" 2>/dev/null)" in
  *package:/data/*) ;;
  *) pm install -r "$APK" >/dev/null 2>&1 ;;
esac

# KernelSU (Next) unmounts module files from app processes that aren't granted root. Keep them
# for centuryplay, in case it ever starts from the system copy.
KSUD=$(command -v ksud 2>/dev/null)
[ -z "$KSUD" ] && [ -x /data/adb/ksud ] && KSUD=/data/adb/ksud
if [ -n "$KSUD" ]; then
  uid=$(stat -c %u "/data/data/$PKG" 2>/dev/null)
  if [ -n "$uid" ] && ! "$KSUD" profile get "$PKG" 2>/dev/null | grep -q '"umount_modules": false'; then
    profile="$MODDIR/.ksu-profile.json"
    printf '{"key":"%s","uid":%s,"allow_su":false,"non_root":{"use_default":false,"umount_modules":false}}\n' \
      "$PKG" "$uid" > "$profile"
    "$KSUD" profile set "$profile"
  fi
fi

# Grant the runtime permissions once, so system mode needs no prompts at all (microphone is
# required by Android for any audio capture, notifications for the streaming notification,
# local network for speaker discovery on Android 17).
[ -f "$MODDIR/.granted" ] && exit 0
for p in RECORD_AUDIO POST_NOTIFICATIONS ACCESS_LOCAL_NETWORK; do
  pm grant "$PKG" "android.permission.$p" 2>/dev/null
done
touch "$MODDIR/.granted"
