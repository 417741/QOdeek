@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 正在打包 QOdeep，请稍候...
call gradlew.bat "一键打包"
echo.
echo 打包结束，APK 已生成到项目根目录：QOdeep.apk
pause
