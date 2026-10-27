@echo off
rem Saves and stops every BlackForgeMC server cleanly.
cd /d "%~dp0"
python tools
etctl.py stop
pause
