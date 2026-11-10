@echo off
set PATH=C:\android-sdk\platform-tools;%PATH%
adb.exe shell input tap 676 570
timeout /t 2 >nul
adb.exe shell input keyevent 4
timeout /t 8 >nul
adb.exe shell screencap -p /sdcard/s.png
adb.exe pull /sdcard/s.png C:\fnos-tablet\shot.png
adb.exe logcat -d -t 400 | findstr /i "ANR not responding com.fnos.tablet"
exit /b 0
