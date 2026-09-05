@echo off
call "%~dp0scripts\push-images-to-registry.bat" %*
exit /b %ERRORLEVEL%
