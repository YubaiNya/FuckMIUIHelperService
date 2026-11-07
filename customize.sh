#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
[ "$BOOTMODE" = true ] || abort "只支持在 Android 中通过 Root 管理器安装。"
[ "$API" -ge 35 ] || abort "需要 Android 15 / API 35 或更新版本。"
FMH_OLD=/data/adb/modules/fuckmiuihelperservice
if [ -d "$FMH_OLD" ] && [ ! -e "$FMH_OLD/disable" ] && [ ! -e "$FMH_OLD/remove" ]; then
  abort "请先停用原版 FuckMIUIHelperService 并重启，避免原版继续删数据或封锁共享 UID。"
fi
# Disabling the old module does not stop its already-running 10-second loop.
for FMH_PROC in /proc/[0-9]*/cmdline; do
  [ -r "$FMH_PROC" ] || continue
  if tr '\000' ' ' < "$FMH_PROC" 2>/dev/null | grep -q '/data/adb/modules/fuckmiuihelperservice/service.sh'; then
    abort "检测到原版脚本仍在运行，请停用原版并重启后安装。"
  fi
done
ui_print "正在检查包级控制和系统事件接口（只读）……"
FMH_PROBE=$(CLASSPATH="$MODPATH/controller.dex" /system/bin/app_process /system/bin io.github.yubainya.fmh.Main probe "$MODPATH" 2>&1) || abort "接口检查失败：$FMH_PROBE"
ui_print "$FMH_PROBE"
FMH_STATE=/data/adb/fmh-safe
mkdir -p "$FMH_STATE" /data/adb/service.d
chmod 700 "$FMH_STATE"
# Install recovery controller atomically; keep the previous valid copy on copy failure.
cp "$MODPATH/controller.dex" "$FMH_STATE/controller.dex.new" || abort "无法保存恢复控制器。"
chmod 600 "$FMH_STATE/controller.dex.new"
mv -f "$FMH_STATE/controller.dex.new" "$FMH_STATE/controller.dex" || abort "无法更新恢复控制器。"
cp "$MODPATH/restore-on-boot.sh" /data/adb/service.d/fmh-safe-restore.sh || abort "无法安装开机恢复脚本。"
chmod 700 /data/adb/service.d/fmh-safe-restore.sh
set_perm_recursive "$MODPATH" 0 0 0755 0644
for FMH_SCRIPT in "$MODPATH"/*.sh; do set_perm "$FMH_SCRIPT" 0 0 0755; done
ui_print "安装完成，重启后生效。目标 APK 和数据会保留。"
ui_print "可在管理器点击操作查看状态；停用或卸载模块会恢复原启用状态。"
