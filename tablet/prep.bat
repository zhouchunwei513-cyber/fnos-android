@echo off
set PATH=C:\android-sdk\platform-tools;%PATH%
echo === size ===
adb.exe shell wm size 1600x1000
adb.exe shell wm density 240
echo === settings.json ===
adb.exe shell rm -f /sdcard/settings.json
adb.exe push C:\fnos-tablet\settings-test.json /sdcard/settings.json
adb.exe shell run-as com.fnos.tablet mkdir -p /data/data/com.fnos.tablet/files
adb.exe shell run-as com.fnos.tablet cp /sdcard/settings.json /data/data/com.fnos.tablet/files/settings.json
adb.exe shell run-as com.fnos.tablet cat /data/data/com.fnos.tablet/files/settings.json
echo === launch ===
adb.exe shell am force-stop com.fnos.tablet
adb.exe shell am start -n com.fnos.tablet/.MainActivity
exit /b 0
