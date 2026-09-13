#!/system/bin/sh
# Install a checked QDCM data override. Composer reads it at the next boot.
set -eu
umask 077
BUILD=RPN_V1.0.0.436_20260722_083524_user
DEST=/data/vendor/display/qdcm_calib_data_il97680a_amoled_panel_without_DSC.json
STATE=/data/vendor/display/nova-calibration-state-v1
# Both input files live in the app-private directory, never shared temporary storage.
SOURCE="$(dirname "$0")/nova-profile-system.json"
LOCK=/data/vendor/display/nova-calibration-lock
FACTORY=/vendor/etc/display/qdcm_calib_data_il97680a_amoled_panel_without_DSC.json
FACTORY_HASH=26a56de100c2a27ecac2fe7ed4d30ba5bcb935865c7470775dcfda744d0173af
GAMMA=c6e20079ef58220960e9511d5641bc02f1bcfb0f90c5bb0a842e5b2ed00c9afd
SRGB=8be527253edcc712006e73a26cbadd4f3aa1ade3bd8d2d00ae38587b3f393b25
fail() { echo "NOVA_NATIVE_ERROR=$1"; exit 1; }
file_sha256() { sha256sum "$1" | cut -d' ' -f1; }
expected() {
    case "$1" in gamma22) echo "$GAMMA" ;; srgb) echo "$SRGB" ;; *) return 1 ;; esac
}
[ "$(id -u)" = 0 ] || fail root_required
action=${1-status}
case "$action" in status|apply|restore) ;; *) fail bad_action ;; esac
# Lock before reading state; another invocation must not act on a stale snapshot.
mkdir -m 700 "$LOCK" || fail operation_busy
trap 'rmdir "$LOCK"' EXIT
trap 'exit 130' HUP INT TERM
compatible=0
if [ "$(getprop ro.build.display.id)" = "$BUILD" ] && [ -f "$FACTORY" ] && [ ! -L "$FACTORY" ]; then
    [ "$(file_sha256 "$FACTORY")" != "$FACTORY_HASH" ] || compatible=1
fi
[ ! -L "$DEST" ] && [ ! -L "$STATE" ] || fail symlink_refused
boot=$(cat /proc/sys/kernel/random/boot_id)
profile=original
saved_boot=none
original_mode=$(settings get system screen_brightness_mode)
original_brightness=$(settings get system screen_brightness)
if [ -e "$STATE" ]; then
    [ -f "$STATE" ] || fail invalid_state
    read -r tag profile saved_boot original_mode original_brightness saved_build < "$STATE"
    [ "$tag" = NOVANATIVE1 ] && [ "$saved_build" = "$BUILD" ] || fail invalid_state
    case "$profile" in original|gamma22|srgb) ;; *) fail invalid_state ;; esac
fi
case "$original_mode" in 0|1) ;; *) fail brightness_mode ;; esac
case "$original_brightness" in ''|*[!0-9]*) fail brightness_value ;; esac
[ "$original_brightness" -le 255 ] || fail brightness_value
if [ -e "$DEST" ]; then
    [ -f "$DEST" ] && [ -f "$STATE" ] || fail foreign_override
    [ "$profile" != original ] || fail inconsistent_state
    [ "$(file_sha256 "$DEST")" = "$(expected "$profile")" ] || fail foreign_override
elif [ "$profile" != original ]; then
    fail missing_override
fi

if [ "$action" = status ]; then
    pending=0
    [ "$saved_boot" != "$boot" ] || pending=1
    echo "NOVA_NATIVE_PROFILE=$profile"
    echo "NOVA_NATIVE_REBOOT_REQUIRED=$pending"
    echo "NOVA_NATIVE_FIRMWARE_COMPATIBLE=$compatible"
    echo NOVA_NATIVE_STATUS_OK=1
    exit 0
fi
case "$action" in apply|restore) ;; *) fail bad_action ;; esac
[ ! -e "$DEST.nova-new" ] && [ ! -L "$DEST.nova-new" ] || fail stale_temporary
[ ! -e "$STATE.nova-new" ] && [ ! -L "$STATE.nova-new" ] || fail stale_temporary
if [ "$action" = apply ]; then
    [ "$compatible" = 1 ] || fail firmware_mismatch
    chosen=${2-}
    chosen_hash=$(expected "$chosen") || fail bad_profile
    [ -f "$SOURCE" ] && [ ! -L "$SOURCE" ] || fail missing_payload
    [ "$(file_sha256 "$SOURCE")" = "$chosen_hash" ] || fail payload_hash
    case "$(getprop persist.sys.sf.color_saturation)" in 1|1.0|1.00|1.000000) ;; *) fail odin_saturation_not_100 ;; esac
else
    chosen=original
fi

# Re-selecting an already saved profile must not reset the user's brightness or require another boot.
if [ "$profile" = "$chosen" ]; then
    pending=0
    [ "$saved_boot" != "$boot" ] || pending=1
    echo "NOVA_NATIVE_PROFILE=$chosen"
    echo NOVA_NATIVE_CONFIG_SAVED=1
    echo "NOVA_NATIVE_REBOOT_REQUIRED=$pending"
    echo NOVA_NATIVE_UNCHANGED=1
    exit 0
fi

had_dest=0
had_state=0
committed=0
backup_ready=0
current_mode=$(settings get system screen_brightness_mode)
current_brightness=$(settings get system screen_brightness)
finish() {
    rc=$?
    trap - EXIT HUP INT TERM
    if [ "$committed" = 0 ] && [ "$backup_ready" = 1 ]; then
        if [ "$had_dest" = 1 ]; then cp -p "$LOCK/dest" "$DEST"; else rm -f "$DEST"; fi
        if [ "$had_state" = 1 ]; then cp -p "$LOCK/state" "$STATE"; else rm -f "$STATE"; fi
        [ ! -f "$DEST" ] || restorecon "$DEST"
        [ ! -f "$STATE" ] || restorecon "$STATE"
        settings put system screen_brightness_mode 0
        settings put system screen_brightness "$current_brightness"
        settings put system screen_brightness_mode "$current_mode"
        echo NOVA_NATIVE_WRITE_ROLLED_BACK=1
    fi
    rm -f "$DEST.nova-new" "$STATE.nova-new" "$LOCK/dest" "$LOCK/state"
    rmdir "$LOCK"
    exit "$rc"
}
trap finish EXIT
trap 'exit 130' HUP INT TERM
if [ -f "$DEST" ]; then cp -p "$DEST" "$LOCK/dest"; had_dest=1; fi
if [ -f "$STATE" ]; then cp -p "$STATE" "$LOCK/state"; had_state=1; fi
backup_ready=1
[ ! -e "$DEST.nova-new" ] && [ ! -L "$DEST.nova-new" ] || fail stale_temporary
[ ! -e "$STATE.nova-new" ] && [ ! -L "$STATE.nova-new" ] || fail stale_temporary
if [ "$action" = apply ]; then
    if [ "$profile" = original ]; then
        original_mode=$current_mode
        original_brightness=$current_brightness
    fi
    cp "$SOURCE" "$DEST.nova-new"
    chown 1000:1003 "$DEST.nova-new"
    chmod 600 "$DEST.nova-new"
    restorecon "$DEST.nova-new"
    [ "$(file_sha256 "$DEST.nova-new")" = "$chosen_hash" ] || fail installed_hash
    mv "$DEST.nova-new" "$DEST"
    restorecon "$DEST"
    settings put system screen_brightness_mode 0
    settings put system screen_brightness 173
    [ "$(settings get system screen_brightness_mode)" = 0 ] &&
        [ "$(settings get system screen_brightness)" = 173 ] || fail brightness_apply
else
    rm -f "$DEST"
    settings put system screen_brightness_mode 0
    settings put system screen_brightness "$original_brightness"
    settings put system screen_brightness_mode "$original_mode"
    [ "$(settings get system screen_brightness_mode)" = "$original_mode" ] &&
        [ "$(settings get system screen_brightness)" = "$original_brightness" ] || fail brightness_restore
fi
printf 'NOVANATIVE1 %s %s %s %s %s\n' "$chosen" "$boot" "$original_mode" "$original_brightness" "$BUILD" > "$STATE.nova-new"
chown 1000:1003 "$STATE.nova-new"
chmod 600 "$STATE.nova-new"
restorecon "$STATE.nova-new"
mv "$STATE.nova-new" "$STATE"
restorecon "$STATE"
sync
committed=1
echo "NOVA_NATIVE_PROFILE=$chosen"
echo NOVA_NATIVE_CONFIG_SAVED=1
echo NOVA_NATIVE_REBOOT_REQUIRED=1
