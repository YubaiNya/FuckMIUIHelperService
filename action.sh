#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
MODDIR=${0%/*}
exec /system/bin/sh "$MODDIR/run.sh" status
