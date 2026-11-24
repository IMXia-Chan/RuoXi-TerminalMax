@echo off
chcp 65001 >nul
rem Build only. Do NOT install: installing restarts the app and kills a running prefix prefill.
rem Double-click keeps the window open; passing "nopause" skips that for scripts.
set "GRADLE_USER_HOME=D:\gradle"
echo === build ===
call "C:\Users\27014\phone-touchpad\gradlew.bat" :app:assembleRelease --console=plain -p C:\Users\27014\phone-touchpad
if errorlevel 1 goto fail
echo.
echo DONE: app\build\outputs\apk\release\app-release.apk
if "%~1"=="" pause
exit /b 0

:fail
echo.
echo FAILED - copy the error above.
if "%~1"=="" pause
exit /b 1
