@echo off
REM Compile and run the Receiver

REM Create bin directory if it doesn't exist
if not exist "bin\Receiver" mkdir "bin\Receiver"

REM Compile to bin directory
echo Compiling Receiver...
javac -d bin\Receiver Receiver\*.java
if %errorlevel% neq 0 (
    echo Compilation failed!
    pause
    exit /b 1
)

REM Edit these parameters as needed:
set SENDER_IP=127.0.0.1
set SENDER_ACK_PORT=8081
set RCV_DATA_PORT=8080
set OUTPUT_FILE=output.txt
set RN=3

echo Running Receiver...
java -cp bin\Receiver Receiver %SENDER_IP% %SENDER_ACK_PORT% %RCV_DATA_PORT% %OUTPUT_FILE% %RN%
pause
