@echo off
REM Clean all compiled files
echo Cleaning compiled files...

REM Delete bin directory
if exist "bin" (
    rmdir /S /Q bin
    echo Deleted bin\ directory
) else (
    echo No bin\ directory found
)

REM Also clean any stray .class files in source folders
del /S /Q Receiver\*.class 2>nul
del /S /Q Sender\*.class 2>nul

echo Done!
pause
