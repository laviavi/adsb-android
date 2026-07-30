@echo off
setlocal enabledelayedexpansion

title ADS-B Android — Build and Install

set PROJECT=D:\sandbox\adsb-android
set ADB=C:\Android\Sdk\platform-tools\adb.exe
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
set ANDROID_HOME=C:\Android\Sdk
set APK=%PROJECT%\app\build\outputs\apk\debug\app-debug.apk

echo ============================================================
echo  ADS-B Android — Build and Install
echo ============================================================
echo.

:: ── Check Java ──────────────────────────────────────────────────────────────
echo [1/4] Checking Java...
"%JAVA_HOME%\bin\java.exe" -version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Java not found at %JAVA_HOME%
    pause & exit /b 1
)
echo       OK

:: ── Check device ─────────────────────────────────────────────────────────────
echo [2/4] Checking for connected Android device...
"%ADB%" start-server >nul 2>&1
"%ADB%" devices 2>nul | findstr /v "List" | findstr /v "^$" > nul
if errorlevel 1 (
    echo.
    echo  No device found. To connect your phone:
    echo    1. Settings ^> About Phone ^> tap Build Number 7 times
    echo    2. Settings ^> Developer Options ^> USB Debugging ON
    echo    3. Plug phone into PC via USB
    echo    4. Accept "Allow USB Debugging" prompt on phone
    echo    5. Run this script again
    echo.
    pause & exit /b 1
)
for /f "tokens=1" %%d in ('"%ADB%" devices ^| findstr /v "List" ^| findstr /v "^$"') do set DEVICE=%%d
echo       OK — Device: %DEVICE%

:: ── Build ────────────────────────────────────────────────────────────────────
echo [3/4] Building APK...
echo.
cd /d "%PROJECT%"
call gradlew.bat :app:assembleDebug --no-daemon
if errorlevel 1 (
    echo ERROR: Build failed.
    pause & exit /b 1
)
if not exist "%APK%" (
    echo ERROR: APK not found at %APK%
    pause & exit /b 1
)
echo.
echo       OK — APK ready

:: ── Install ───────────────────────────────────────────────────────────────────
echo [4/4] Installing on device...
"%ADB%" install -r "%APK%"
if errorlevel 1 (
    echo ERROR: Install failed. Check USB debugging is enabled.
    pause & exit /b 1
)

echo.
echo ============================================================
echo  SUCCESS — ADS-B Receiver installed
echo ============================================================
echo.
echo  Launching app...
"%ADB%" shell am start -n "com.laviavi.adsbandroid.debug/com.laviavi.adsbandroid.ui.MainActivity"
echo.
pause
