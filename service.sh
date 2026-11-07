#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
MODDIR=${0%/*}
umask 077
# Root frameworks already run this late_start hook asynchronously.
exec /system/bin/sh "$MODDIR/run.sh" resume >/dev/null 2>&1
