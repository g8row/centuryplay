# centuryplay system mode: Magisk / KernelSU / APatch installer (POSIX sh).
SKIPUNZIP=0
PKG=com.airplay.streamer

if [ "$API" -lt 30 ]; then
  abort "! centuryplay needs android 11 or newer"
fi

ui_print "- installing centuryplay as a privileged system app"
case "$ARCH" in
  arm64) ARCH_DIR=arm64; ABI=arm64-v8a ;;
  arm) ARCH_DIR=arm; ABI=armeabi-v7a ;;
  x64) ARCH_DIR=x86_64; ABI=x86_64 ;;
  *) abort "! unsupported architecture $ARCH" ;;
esac

# System apps don't extract compressed native libraries: ship the one for this device next
# to the apk, the way the ROM build would.
APK=$MODPATH/system/priv-app/CenturyPlay/CenturyPlay.apk
LIBDIR=$MODPATH/system/priv-app/CenturyPlay/lib/$ARCH_DIR
mkdir -p "$LIBDIR"
unzip -oj "$APK" "lib/$ABI/*" -d "$LIBDIR" >&2 || abort "! the apk has no $ABI library"

# The copy under /system is what makes centuryplay privileged, but root solutions hide module
# files from app processes (and SystemUI, the launcher...). So the app also runs from /data, as
# an update of the system copy: same apk, privileged grants kept, visible to everyone.
ui_print "- installing the app itself"
out=$(pm install -r "$APK" 2>&1)
case "$out" in
  *Success*) ;;
  *UPDATE_INCOMPATIBLE*)
    abort "! an installed centuryplay is signed with another key: uninstall it first" ;;
  *VERSION_DOWNGRADE*)
    ui_print "- a newer centuryplay is already installed: keeping it" ;;
  *)
    ui_print "! could not install the app now ($out); it is retried at boot" ;;
esac

set_perm_recursive "$MODPATH/system" 0 0 0755 0644
ui_print "- done. reboot, then open centuryplay: settings shows \"system mode\"."
