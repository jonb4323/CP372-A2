@echo off
REM Compile and run the Sender

REM Create bin directory if it doesn't exist
if not exist "bin\Sender" mkdir "bin\Sender"

REM Compile to bin directory
echo Compiling Sender...
javac -d bin\Sender Sender\*.java
if %errorlevel% neq 0 (
    echo Compilation failed!
    pause
    exit /b 1
)

REM Edit these parameters as needed:
set RCV_IP=127.0.0.1
set RCV_DATA_PORT=8080
set SENDER_ACK_PORT=8081
set INPUT_FILE=input.txt
set TIMEOUT_MS=500
set WINDOW_SIZE=4

echo Running Sender...
java -cp bin\Sender Sender %RCV_IP% %RCV_DATA_PORT% %SENDER_ACK_PORT% %INPUT_FILE% %TIMEOUT_MS% %WINDOW_SIZE%
pause
