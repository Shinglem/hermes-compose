@echo off
rem Compatibility shim: the real logic lives in App.bat (single source of args).
call "%~dp0App.bat" --console %*