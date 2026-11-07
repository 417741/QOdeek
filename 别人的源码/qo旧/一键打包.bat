@echo off
setlocal
cd /d "%~dp0"

echo ============================================
echo   App Manager - Build Release APK
echo ============================================
echo.

echo [1/2] Building release APK...
if not exist "%~dp0gradlew.bat" (
    echo [FAILED] gradlew.bat not found in project root.
    echo Please restore the Gradle wrapper files.
    pause
    exit /b 1
)
call "%~dp0gradlew.bat" assembleRelease --parallel --daemon
if errorlevel 1 (
    echo.
    echo [FAILED] Build error. See messages above.
    pause
    exit /b 1
)

echo.
echo [2/2] Checking output APK...

:: Gradle 的 copyReleaseApkToRoot 任务已把产物复制成 QO.apk（见 app/build.gradle.kts），
:: 这里只做校验，不再重复复制（否则会多出一个 app-release-vX.Y.Z.apk）
set "DST=QO.apk"
if not exist "%DST%" (
    echo [FAILED] APK not found: %DST%
    pause
    exit /b 1
)

echo.
echo ============================================
echo   DONE! Release APK saved to:
echo   %CD%\%DST%
echo ============================================
echo.
pause
