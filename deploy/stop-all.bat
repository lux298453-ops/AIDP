@echo off
rem Stop nginx and backend
cd /d D:\nginx\nginx-1.30.4
nginx.exe -s quit 2>nul
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:":8080 .*LISTENING"') do (
    echo [stop-all] killing backend PID=%%p
    taskkill /pid %%p /f >nul 2>&1
)
echo [stop-all] done
pause
exit /b 0
