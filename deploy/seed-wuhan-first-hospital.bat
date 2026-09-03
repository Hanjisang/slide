@echo off
setlocal
chcp 65001 >nul
title Seed Wuhan First Hospital Demo Data
cd /d "%~dp0.."

echo ============================================================
echo  Wuhan First Hospital synthetic dataset
echo  Date range: 2026-01-01 through 2026-08-30
echo  Volume:     300 records per day, about 72,600 records
echo ============================================================
echo.
echo This creates anonymized synthetic records only.
echo Digital slides use METADATA_ONLY and contain no real files.
echo.

docker compose ps mysql | findstr /I "healthy" >nul
if errorlevel 1 (
    echo [FAILED] MySQL container is not healthy. Start the stack first.
    pause
    exit /b 1
)

echo Importing data. This can take several minutes...
docker compose exec -T mysql sh -lc "MYSQL_PWD=\"$MYSQL_PASSWORD\" mysql --default-character-set=utf8mb4 -u\"$MYSQL_USER\" \"$MYSQL_DATABASE\"" < "deploy\mysql\wuhan-first-hospital-2026-seed.sql"
set "SEED_EXIT=%ERRORLEVEL%"

echo.
if not "%SEED_EXIT%"=="0" (
    echo [FAILED] Dataset import failed with exit code %SEED_EXIT%.
) else (
    echo [SUCCESS] Wuhan First Hospital dataset is ready.
    echo Open the medical data, collection, quality, and digital slide pages to verify it.
)
echo.
pause
exit /b %SEED_EXIT%
