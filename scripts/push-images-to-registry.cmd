@echo off
call "%~dp0push-images-to-registry.bat" %*
exit /b %ERRORLEVEL%
