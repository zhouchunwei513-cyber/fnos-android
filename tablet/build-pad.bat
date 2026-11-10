@echo off
set "JAVA_HOME=C:\fnos-tools\jdk-17.0.13+11"
set "ANDROID_HOME=C:\android-sdk"
set "ANDROID_SDK_ROOT=C:\android-sdk"
cd /d C:\fnos-tablet
C:\fnos-tools\gradle-8.7\bin\gradle.bat assembleDebug --no-build-cache --gradle-user-home C:\fnos-cache\gradle --no-daemon --offline
