@echo off
set "PY=D:\AI\pythonw.exe"
set "SC=%~dp0wardrobe_push.py"
if not exist "%PY%" (
  echo [wardrobe] pythonw.exe not found: %PY%
  echo Edit this .bat and point PY at your python.
  pause
  exit /b 1
)
if not exist "%SC%" (
  echo [wardrobe] script not found: %SC%
  pause
  exit /b 1
)
start "" "%PY%" "%SC%"
