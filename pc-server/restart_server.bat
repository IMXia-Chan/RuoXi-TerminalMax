@echo off
rem Restart the super-terminal server with the updated transfer station.
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":9527 " ^| findstr "LISTENING"') do taskkill /PID %%p /F >nul 2>&1
timeout /t 1 /nobreak >nul
cd /d C:\Users\27014\phone-touchpad\pc-server
start "" pythonw server.py --background
