@echo off
set PATH=C:\android-sdk\platform-tools;%PATH%
echo === push skin ===
adb.exe push C:\fnos-tablet\skin\tablet-test\manifest.json /sdcard/sk_manifest.json
adb.exe push C:\fnos-tablet\skin\tablet-test\bg.png /sdcard/sk_bg.png
adb.exe shell "run-as com.fnos.tablet mkdir -p files/skins/tablet-test"
adb.exe shell "cat /sdcard/sk_manifest.json | run-as com.fnos.tablet sh -c 'cat > files/skins/tablet-test/manifest.json'"
adb.exe shell "cat /sdcard/sk_bg.png | run-as com.fnos.tablet sh -c 'cat > files/skins/tablet-test/bg.png'"
adb.exe shell run-as com.fnos.tablet ls -l files/skins/tablet-test
echo === push settings ===
adb.exe push C:\fnos-tablet\settings-skin.json /sdcard/settings.json
adb.exe shell "cat /sdcard/settings.json | run-as com.fnos.tablet sh -c 'cat > files/settings.json'"
adb.exe shell run-as com.fnos.tablet cat files/settings.json
echo === relaunch ===
adb.exe shell am force-stop com.fnos.tablet
adb.exe shell am start -n com.fnos.tablet/.MainActivity
timeout /t 22 >nul
adb.exe shell screencap -p /sdcard/s.png
adb.exe pull /sdcard/s.png C:\fnos-tablet\shot.png
echo === skin log ===
adb.exe shell run-as com.fnos.tablet cat files/startup.log | findstr /i "skin titlebar.inject titlebar-inject"
exit /b 0
