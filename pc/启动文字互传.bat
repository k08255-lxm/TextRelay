@echo off
chcp 65001 >nul
cd /d "%~dp0"
where python >nul 2>nul
if %errorlevel%==0 (
  python textrelay_pc.py
  pause
  exit /b
)
where py >nul 2>nul
if %errorlevel%==0 (
  py textrelay_pc.py
  pause
  exit /b
)
echo 未找到 Python，请先安装 Python（安装时勾选 Add to PATH）
echo 下载地址：https://www.python.org/downloads/
pause
