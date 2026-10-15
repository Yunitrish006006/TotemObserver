#!/usr/bin/env bash
set -euo pipefail

export SDL_VIDEO_DRIVER=x11
if [[ -z "${VK_DRIVER_FILES:-}" ]]; then
    for driver in /usr/share/vulkan/icd.d/lvp_icd*.json; do
        if [[ -f "$driver" ]]; then export VK_DRIVER_FILES="$driver"; break; fi
    done
fi
export VK_ICD_FILENAMES="${VK_DRIVER_FILES:-}"
display="${TOTEM_GLX_DISPLAY:-:99}"
config_dir="$(mktemp -d)"
config="$config_dir/xorg.conf"
log="$config_dir/xorg.log"
cat >"$config" <<'EOF'
Section "ServerFlags"
    Option "AutoAddDevices" "false"
    Option "AutoEnableDevices" "false"
    Option "AllowMouseOpenFail" "true"
EndSection

Section "ServerLayout"
    Identifier "TotemLayout"
    Screen 0 "TotemScreen"
EndSection
Section "Device"
    Identifier "TotemDummy"
    Driver "dummy"
    VideoRam 256000
    Option "ConstantDPI" "true"
EndSection
Section "Monitor"
    Identifier "TotemMonitor"
    HorizSync 5-1000
    VertRefresh 5-200
EndSection
Section "Screen"
    Identifier "TotemScreen"
    Device "TotemDummy"
    Monitor "TotemMonitor"
    DefaultDepth 24
    DefaultFbBpp 32
    Option "AllowEmptyInitialConfiguration" "true"
    SubSection "Display"
        Depth 24
        Visual "TrueColor"
        Virtual 1280 720
        Modes "1280x720"
    EndSubSection
EndSection
EOF
sudo Xorg "$display" -ac -noreset -config "$config" +extension GLX +extension RANDR +extension RENDER +iglx >"$log" 2>&1 &
xorg_pid=$!
client_pid=""
cleanup() {
    trap - EXIT TERM INT
    set +e
    if [[ -n "$client_pid" ]]; then
        kill -TERM "$client_pid" 2>/dev/null || true
        wait "$client_pid" 2>/dev/null || true
    fi
    sudo kill "$xorg_pid" 2>/dev/null || true
    wait "$xorg_pid" 2>/dev/null || true
    rm -rf "$config_dir"
}
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
for _ in $(seq 1 30); do
    if DISPLAY="$display" xdpyinfo >/dev/null 2>&1; then
        DISPLAY="$display" "$@" &
        client_pid=$!
        status=0
        wait "$client_pid" || status=$?
        client_pid=""
        exit "$status"
    fi
    if ! kill -0 "$xorg_pid" 2>/dev/null; then
        cat "$log" >&2 || true
        exit 1
    fi
    sleep 1
done
cat "$log" >&2 || true
exit 1
