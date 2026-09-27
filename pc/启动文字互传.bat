@echo off
cd /d "%~dp0"
py -3 --version >nul 2>nul
if %errorlevel%==0 (
    py -3 textrelay_pc.py %*
    pause
    exit /b
)
python --version >nul 2>nul
if %errorlevel%==0 (
    python textrelay_pc.py %*
    pause
    exit /b
)
echo [错误] 未找到 Python，请先安装 Python 3（安装时勾选 Add to PATH / py 启动器）
echo 下载地址：https://www.python.org/downloads/
pause
