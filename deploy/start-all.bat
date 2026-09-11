@echo off
rem Start backend + nginx. PostgreSQL runs as a Windows service (auto start).
call "%~dp0start-backend.bat"
call "%~dp0start-nginx.bat"
exit /b 0
