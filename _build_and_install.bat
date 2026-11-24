@echo off
chcp 65001 >nul
cd /d C:\Users\27014\phone-touchpad
set GRADLE_USER_HOME=D:\gradle
echo === build ===
call gradlew.bat :app:assembleRelease --console=plain -p C:\Users\27014\phone-touchpad
if errorlevel 1 goto fail
echo.
echo === install (keep data, no uninstall) ===
adb install -r "C:\Users\27014\phone-touchpad\app\build\outputs\apk\release\app-release.apk"
if errorlevel 1 goto fail
echo.
echo === version ===
adb shell dumpsys package com.conmarn.app | findstr versionCode
echo.
echo DONE.
pause
exit /b 0

:fail
echo.
echo FAILED - copy the error above.
pause
exit /b 1
