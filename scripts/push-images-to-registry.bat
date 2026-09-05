@echo off
setlocal
cd /d "%~dp0.."

echo Medical Report Platform - local image publisher
echo.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0push-images-to-registry.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"
echo.
if "%EXIT_CODE%"=="0" (
  echo Push workflow finished.
) else (
  echo Push workflow failed. Exit code: %EXIT_CODE%
)
echo.
pause
exit /b %EXIT_CODE%
