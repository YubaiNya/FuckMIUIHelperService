#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
umask 077
FMH_STATE=/data/adb/fmh-safe
[ -f "$FMH_STATE/controller.dex" ] || exit 0
# Pause immediately, even if Android services have not started yet.
: > "$FMH_STATE/paused"
export CLASSPATH="$FMH_STATE/controller.dex"
/system/bin/app_process /system/bin io.github.yubainya.fmh.Main restore /data/adb/modules/fuckmiuihelperservice_safe >"$FMH_STATE/restore.log" 2>&1 &
# Keep original facts and the boot recovery hook; never erase failed recovery evidence.
exit 0
