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

# A missing property means 1.0 in SurfaceFlinger, not an OdinTools error.
# OdinTools can also change the live transform without updating this property.
# Read both independently. Only the explicit reset-saturation action changes them.
read_color_state() {
    saturation_state=unavailable
    saturation_value='<unavailable>'
    if saturation_value=$(getprop persist.sys.sf.color_saturation); then
        if [ -z "$saturation_value" ]; then
            saturation_value='<default:1.0>'
            saturation_state=default
        else
            saturation_state=$(printf '%s\n' "$saturation_value" | LC_ALL=C awk '
                NR != 1 { invalid=1 }
                NR == 1 {
                    if ($0 !~ /^[+]?[0-9]+([.][0-9]*)?([eE][+-]?[0-9]+)?$/) invalid=1
                    neutral=($0 + 0 == 1)
                }
                END { print invalid ? "invalid" : (neutral ? "neutral" : "modified") }
            ')
        fi
    fi
    # Keep malformed property contents out of the line-oriented operation log.
    case "$saturation_state" in invalid|unavailable) saturation_value="<$saturation_state>" ;; esac
    printf 'NOVA_SATURATION_PERSISTENT=%s\n' "$saturation_value"
    printf 'NOVA_SATURATION_PERSISTENT_STATUS=%s\n' "$saturation_state"

    transform_state=unavailable
    # Bounded read. Do not store the full dump (it contains unrelated app details).
    if sf_dump=$(dumpsys -t 5 SurfaceFlinger 2>/dev/null); then
        transform=$(printf '%s\n' "$sf_dump" | LC_ALL=C awk '
            /^Display / { internal=0 }
            /layerFilter=.*toInternalDisplay=true/ { internal=1 }
            internal && /^[[:space:]]*colorTransformMatrix=/ {
                sub(/^[[:space:]]*colorTransformMatrix=/, "")
                gsub(/[[:space:]]/, "")
                print
            }
        ')
        unset sf_dump
        # This is the internal display only, not an external/virtual display or layer.
        # The firmware prints matrix components to three decimal places.
        transform=$(printf '%s' "$transform" | sed 's/-0\.000/0.000/g')
        if [ "$transform" = '[[1.000,0.000,0.000,0.000][0.000,1.000,0.000,0.000][0.000,0.000,1.000,0.000][0.000,0.000,0.000,1.000]]' ]; then
            transform_state=identity
        elif printf '%s\n' "$transform" | LC_ALL=C awk '
            BEGIN { row="\\[[-]?[0-9]+[.][0-9]+,[-]?[0-9]+[.][0-9]+,[-]?[0-9]+[.][0-9]+,[-]?[0-9]+[.][0-9]+\\]" }
            NR == 1 && $0 ~ ("^\\[" row row row row "\\]$") { valid=1 }
            END { exit !(NR == 1 && valid) }
        '; then
            transform_state=modified
        fi
    fi
    printf 'NOVA_COLOR_TRANSFORM_STATUS=%s\n' "$transform_state"
}

require_neutral_color_state() {
    read_color_state
    case "$saturation_state" in
        default|neutral|modified) ;;
        *) fail color_state_unavailable ;;
    esac
    # Android 13 ColorDisplayService compares this STRING with "1.0" when
    # inferring its startup mode. Empty, "1", and "1.000" can select boosted
    # mode (1.1) at boot even while the current SurfaceFlinger matrix is identity.
    [ "$saturation_value" = 1.0 ] || fail saturation_setup_required
    case "$transform_state" in
        identity) ;;
        modified) fail color_transform_active ;;
        *) fail color_state_unavailable ;;
    esac
}
[ "$(id -u)" = 0 ] || fail root_required
action=${1-status}
case "$action" in status|apply|restore|reset-saturation) ;; *) fail bad_action ;; esac
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
    read_color_state
    echo NOVA_NATIVE_STATUS_OK=1
    exit 0
fi
if [ "$action" = reset-saturation ]; then
    [ "$compatible" = 1 ] || fail firmware_mismatch
    # Separate, user-confirmed operation. Never resets night-light, accessibility,
    # another app's preferences, the panel profile, or the display color mode.
    setprop persist.sys.sf.color_saturation 1.0 || fail saturation_reset_failed
    service call SurfaceFlinger 1022 f 1.0 >/dev/null || fail saturation_reset_failed
    sleep 0.2
    require_neutral_color_state
    echo NOVA_NATIVE_SATURATION_RESET=1
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
    require_neutral_color_state
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
