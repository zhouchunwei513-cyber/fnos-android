@echo off
set PATH=C:\android-sdk\platform-tools;%PATH%
adb.exe shell screencap -p /sdcard/s.png
adb.exe pull /sdcard/s.png C:\fnos-tablet\shot.png
echo === log ===
adb.exe shell run-as com.fnos.tablet cat /data/data/com.fnos.tablet/files/startup.log
exit /b 0
