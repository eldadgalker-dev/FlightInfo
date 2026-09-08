@echo off
REM Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
REM This software is released under the BSD 3-Clause License.
REM See the LICENSE.txt file in the project root for full license information.
REM =============================================================
REM FlightInfo - update_github.bat
REM Version 1.0
REM Purpose : Double-click launcher for update_github.ps1. Drag a project
REM           zip onto this file to publish it; run without a file to get
REM           a file-selection dialog. Add "tag" as a second word to also
REM           create a GitHub Release:  update_github.bat x.zip tag
REM =============================================================
setlocal
set "SCRIPT=%~dp0update_github.ps1"
set "TAGARG="
if /I "%~2"=="tag" set "TAGARG=-Tag"
if "%~1"=="" (
    powershell -NoProfile -STA -ExecutionPolicy Bypass -File "%SCRIPT%" %TAGARG%
) else (
    powershell -NoProfile -STA -ExecutionPolicy Bypass -File "%SCRIPT%" -ZipPath "%~1" %TAGARG%
)
endlocal
