# 一次性：找出 Dev-C++ 装在哪，顺带找 gcc.exe
$lnk = 'C:\ProgramData\Microsoft\Windows\Start Menu\Programs\Bloodshed Dev-C++\Dev-C++.lnk'
$sh = New-Object -ComObject WScript.Shell
$s = $sh.CreateShortcut($lnk)
Write-Output ("TARGET " + $s.TargetPath)
$root = Split-Path -Parent $s.TargetPath
Write-Output ("ROOT   " + $root)
Get-ChildItem -Path $root -Recurse -Filter gcc.exe -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Output ("GCC    " + $_.FullName) }
