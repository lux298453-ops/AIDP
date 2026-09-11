@echo off
rem Start backend jar if not already running. Log: prd-assist-backend\logs\backend.log
netstat -ano | findstr /r /c:":8085 .*LISTENING" >nul
if %errorlevel%==0 (
    echo [start-backend] port 8085 already in use, skip
    exit /b 0
)
cd /d D:\idea\Project\AiDocumentPlatform\prd-assist-backend
if not exist logs mkdir logs
start "" "D:\JDK\jdk-17.0.12\bin\javaw.exe" -jar target\AiDocumentPlatform-0.0.1-SNAPSHOT.jar --logging.file.name=D:\idea\Project\AiDocumentPlatform\prd-assist-backend\logs\backend.log
echo [start-backend] backend starting, log: prd-assist-backend\logs\backend.log
exit /b 0
