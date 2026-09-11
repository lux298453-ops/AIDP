@echo off
rem Start nginx if not already running
tasklist /fi "imagename eq nginx.exe" | findstr /i nginx.exe >nul
if %errorlevel%==0 (
    echo [start-nginx] nginx already running, skip
    exit /b 0
)
cd /d D:\nginx\nginx-1.30.4
start "" nginx.exe
echo [start-nginx] nginx started
exit /b 0
