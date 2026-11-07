#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
MODDIR=${0%/*}
umask 077
export CLASSPATH="$MODDIR/controller.dex"
exec /system/bin/app_process /system/bin io.github.yubainya.fmh.Main "${1:-status}" "$MODDIR"
