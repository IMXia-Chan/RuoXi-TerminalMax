@echo off
rem 退出后台静默服务(仅结束 server.py --background 的进程,不碰其它 python/pythonw)。
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='pythonw.exe'\" | Where-Object { $_.CommandLine -like '*server.py*--background*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Output ('已结束 PID ' + $_.ProcessId) }"
pause
