@echo off
set PATH=C:\android-sdk\platform-tools;%PATH%
echo === install ===
adb.exe install -r C:\fnos-tablet\app\build\outputs\apk\debug\app-debug.apk
echo === assets in apk ===
C:\fnos-tools\jdk-17.0.13+11\bin\jar.exe tf C:\fnos-tablet\app\build\outputs\apk\debug\app-debug.apk | findstr /i titlebar
echo === device ===
adb.exe devices
exit /b 0
