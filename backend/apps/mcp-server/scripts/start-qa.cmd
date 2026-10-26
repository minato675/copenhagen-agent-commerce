@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-qa.ps1" %*
exit /b %errorlevel%
