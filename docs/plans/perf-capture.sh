#!/usr/bin/env bash
# 用法: perf-capture.sh cold | start-scroll | stop-scroll
# 场景: cold=冷启动3次取中位; start-scroll=重置帧统计(随后人工滚5屏); stop-scroll=输出帧统计
ADB="/c/Users/a'su's/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV="89a5f9e1"
PKG="com.example.rinklnote"
case "$1" in
  cold)
    for i in 1 2 3; do
      "$ADB" -s $DEV shell am force-stop $PKG; sleep 2
      "$ADB" -s $DEV shell am start -W -n $PKG/.MainActivity | grep -E "^TotalTime"
      sleep 3
    done ;;
  start-scroll) "$ADB" -s $DEV shell dumpsys gfxinfo $PKG reset ;;
  stop-scroll)  "$ADB" -s $DEV shell dumpsys gfxinfo $PKG | sed -n '/Total frames rendered/,/95th/p' ;;
  *) echo "用法: perf-capture.sh cold | start-scroll | stop-scroll" >&2; exit 1 ;;
esac
