@echo off
rem Starts the BlackForgeMC proxy + all 5 servers in the background (no windows).
cd /d "%~dp0"
python tools
etctl.py start
timeout /t 25 /nobreak >nul
python tools
etctl.py status
pause
