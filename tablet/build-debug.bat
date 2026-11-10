@echo off
set JAVA_HOME=C:\fnos-tools\jdk-17.0.13+11
set ANDROID_HOME=C:\android-sdk
cd /d C:\fnos-tablet
C:\fnos-tools\gradle-8.7\bin\gradle.bat assembleDebug --gradle-user-home C:\fnos-cache\gradle --no-daemon --offline
exit /b %ERRORLEVEL%
