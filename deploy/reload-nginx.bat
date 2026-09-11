@echo off
rem Run after editing nginx.conf: validate config first, then hot reload.
cd /d D:\nginx\nginx-1.30.4
nginx.exe -t
if errorlevel 1 (
    echo.
    echo [reload-nginx] config test FAILED, reload skipped. Fix errors above.
    pause
    exit /b 1
)
nginx.exe -s reload
echo [reload-nginx] config reloaded
pause
exit /b 0
