@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0init_gradle_wrapper.ps1"
if errorlevel 1 exit /b 1
