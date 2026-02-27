@echo off
setlocal enabledelayedexpansion

REM Check parameters
if "%~1"=="" (
    echo Usage: %0 ^<JS_file_path^> [function_name] [parameters_JSON] [env_file_path]
    echo Example: %0 example.js
    echo Example: %0 example.js main
    echo Example: %0 example.js main "{\"name\":\"John\"}"
    echo Example: %0 example.js main "{}" .env.local
    exit /b 1
)

REM Parameters
set "FILE_PATH=%~1"
if "%~2"=="" (
    set "FUNCTION_NAME=main"
    set "PARAMS={}"
    set "ENV_FILE_PATH="
) else (
    set "FUNCTION_NAME=%~2"
    if "%~3"=="" (
        set "PARAMS={}"
        set "ENV_FILE_PATH=%~4"
    ) else (
        set "PARAMS=%~3"
        set "ENV_FILE_PATH=%~4"
    )
)

REM Check if file exists
if not exist "%FILE_PATH%" (
    echo Error: File does not exist - %FILE_PATH%
    exit /b 1
)

REM Check ADB availability
adb version >nul 2>&1 || (
    echo Error: ADB command not found.
    echo Make sure Android SDK is installed and adb is in PATH
    exit /b 1
)

REM Device detection
set "DEVICE_SERIAL="
set "DEVICE_COUNT=0"
echo Checking connected devices...

REM Get valid devices list
for /f "skip=1 tokens=1,2" %%a in ('adb devices') do (
    if "%%b" == "device" (
        set /a DEVICE_COUNT+=1
        set "DEVICE_!DEVICE_COUNT!=%%a"
        echo [!DEVICE_COUNT!] %%a
    )
)

if %DEVICE_COUNT% equ 0 (
    echo Error: No authorized devices found
    exit /b 1
)

REM Device selection
if %DEVICE_COUNT% equ 1 (
    set "DEVICE_SERIAL=!DEVICE_1!"
    echo Using the only connected device: !DEVICE_SERIAL!
) else (
    :device_menu
    echo Multiple devices detected:
    for /l %%i in (1,1,%DEVICE_COUNT%) do echo   %%i. !DEVICE_%%i!
    set/p "CHOICE=Select device (1-%DEVICE_COUNT%): "
    
    REM Validate input
    echo !CHOICE!|findstr /r "^[1-9][0-9]*$" >nul || (
        echo Invalid input. Numbers only.
        goto :device_menu
    )
    set /a CHOICE=!CHOICE! >nul
    if !CHOICE! lss 1 (
        echo Number too small
        goto :device_menu
    )
    if !CHOICE! gtr %DEVICE_COUNT% (
        echo Number too large
        goto :device_menu
    )
    
    for %%i in (!CHOICE!) do set "DEVICE_SERIAL=!DEVICE_%%i!"
    echo Selected device: !DEVICE_SERIAL!
)

REM File operations
echo Creating directory structure...
adb -s "!DEVICE_SERIAL!" shell mkdir -p "/sdcard/Android/data/com.ai.assistance.operit/js_temp"

for %%F in ("%FILE_PATH%") do set "TARGET_FILE=/sdcard/Android/data/com.ai.assistance.operit/js_temp/%%~nxF"

echo Pushing [%FILE_PATH%] to device...
adb -s "!DEVICE_SERIAL!" push "%FILE_PATH%" "!TARGET_FILE!"
if errorlevel 1 (
    echo Error: Failed to push file
    exit /b 1
)

REM Resolve env file path
if "!ENV_FILE_PATH!"=="" (
    for %%F in ("%FILE_PATH%") do set "SCRIPT_DIR=%%~dpF"
    if exist "!SCRIPT_DIR!.env.local" (
        set "ENV_FILE_PATH=!SCRIPT_DIR!.env.local"
    )
)

set "TARGET_ENV_FILE="
set "HAS_ENV_FILE=false"
if not "!ENV_FILE_PATH!"=="" (
    if exist "!ENV_FILE_PATH!" (
        for %%E in ("!ENV_FILE_PATH!") do set "TARGET_ENV_FILE=/sdcard/Android/data/com.ai.assistance.operit/js_temp/%%~nxE"
        echo Pushing env file [!ENV_FILE_PATH!] to device...
        adb -s "!DEVICE_SERIAL!" push "!ENV_FILE_PATH!" "!TARGET_ENV_FILE!"
        if errorlevel 1 (
            echo Error: Failed to push env file
            exit /b 1
        )
        set "HAS_ENV_FILE=true"
    )
)

REM Escape JSON quotes
set "PARAMS=!PARAMS:"=\"!"

REM Execute JS function
echo Executing [!FUNCTION_NAME!] with params: !PARAMS!
if "!HAS_ENV_FILE!"=="true" (
    adb -s "!DEVICE_SERIAL!" shell "am broadcast -a com.ai.assistance.operit.EXECUTE_JS -n com.ai.assistance.operit/.core.tools.javascript.ScriptExecutionReceiver --include-stopped-packages --es file_path '!TARGET_FILE!' --es function_name '!FUNCTION_NAME!' --es params '!PARAMS!' --es env_file_path '!TARGET_ENV_FILE!' --ez temp_file true --ez temp_env_file true"
) else (
    adb -s "!DEVICE_SERIAL!" shell "am broadcast -a com.ai.assistance.operit.EXECUTE_JS -n com.ai.assistance.operit/.core.tools.javascript.ScriptExecutionReceiver --include-stopped-packages --es file_path '!TARGET_FILE!' --es function_name '!FUNCTION_NAME!' --es params '!PARAMS!' --ez temp_file true"
)

echo Waiting for execution to complete...
timeout /t 2 >nul

echo Capturing logcat output for JsEngine tag (Press Ctrl+C to stop):
adb -s "!DEVICE_SERIAL!" logcat -s JsEngine:*