#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# One check per boot, never a recurring task. Kept outside the module for rollback.
umask 077
FMH_MOD=/data/adb/modules/fuckmiuihelperservice_safe
FMH_STATE=/data/adb/fmh-safe
if [ -d "$FMH_MOD" ] && [ ! -e "$FMH_MOD/disable" ] && [ ! -e "$FMH_MOD/remove" ]; then
  exit 0
fi
[ -f "$FMH_STATE/controller.dex" ] || exit 0
[ -f "$FMH_STATE/baseline.json" ] || [ -f "$FMH_STATE/baseline.json.bak" ] || exit 0
export CLASSPATH="$FMH_STATE/controller.dex"
# waitForService inside the controller blocks on Binder service registration.
# Backgrounding avoids delaying the root manager's boot hooks.
/system/bin/app_process /system/bin io.github.yubainya.fmh.Main restore "$FMH_MOD" >"$FMH_STATE/restore.log" 2>&1 &
exit 0
