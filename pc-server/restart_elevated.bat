@echo off
chcp 936 >nul
rem 重启 pc-server。右键 → 以管理员身份运行。
rem 全过程写进 restart_log.txt —— 窗口闪退也没关系,事后能查。
rem
rem 2026-10-02 的教训:上一版把 taskkill 的输出丢进 nul,于是「没提权 →
rem taskkill 失败 → 新进程起来发现端口被占又自己退出」这整条链路**一声不吭**,
rem 用户只看到黑框闪了一下,旧进程纹丝不动,无从排查。**错误不能被吞掉。**
rem
rem 2026-10-06 的教训:这个文件原来存成了 Unix 换行(LF)。cmd.exe 读 .bat 时
rem 按「每行 2 字节换行」算偏移,FF 一偏就把半截词当命令执行 ——
rem 症状是「黑框闪一下、什么都没干、日志一个字都没写」,而且每次都一样。
rem ★ 以后凡是用工具生成 .bat,必须转成 CRLF,不然它从第一行起就是坏的。
setlocal enabledelayedexpansion
set PORT=9527
set DIR=C:\Users\27014\phone-touchpad\pc-server
set PY=D:\AI\pythonw.exe
set LOG=%DIR%\restart_log.txt

echo ==== %DATE% %TIME% ==== > "%LOG%"

rem ── 先自证身份。net session 只有提权后才成功,这是最省事的判据。
net session >nul 2>&1
if errorlevel 1 (
  echo [x] **没有提权** —— 这次运行是普通权限,干不掉旧进程。 >> "%LOG%"
  echo     请关掉这个窗口,回到文件夹,**右键**这个文件,选「以管理员身份运行」。 >> "%LOG%"
  echo     ^(直接双击是没用的 —— 直接双击就是普通权限,这正是上次失败的原因。^) >> "%LOG%"
  type "%LOG%"
  pause
  exit /b 1
)
echo [ok] 已提权。 >> "%LOG%"

if not exist "%PY%" (
  echo [x] 找不到解释器:%PY% >> "%LOG%"
  pause
  exit /b 1
)

rem ── 按端口找旧进程
set KILLED=0
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%PORT% " ^| findstr "LISTENING"') do (
  echo [*] 结束占用 %PORT% 的 PID %%p >> "%LOG%"
  taskkill /PID %%p /F >> "%LOG%" 2>&1
  set KILLED=1
)
if "!KILLED!"=="0" echo [*] %PORT% 上本来没有进程在听 >> "%LOG%"

rem 等端口真的释放 —— taskkill 返回不代表监听已撤,抢跑会起不来
timeout /t 3 /nobreak >nul

cd /d "%DIR%"
start "" "%PY%" "%DIR%\server.py" --background
timeout /t 3 /nobreak >nul

netstat -ano | findstr ":%PORT% " | findstr "LISTENING" >nul
if errorlevel 1 (
  echo [x] 起是起了,但 %PORT% 没在听 —— 看 %DIR%\server.log >> "%LOG%"
) else (
  echo [ok] pc-server 已重启,正在监听 %PORT% >> "%LOG%"
)

type "%LOG%"
echo.
echo (日志也留在 %LOG%)
pause
