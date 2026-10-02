@echo off
rem Copyright 2023-2025 RWPP contributors. GNU AGPLv3: https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
setlocal
cd /d "%~dp0"
where javaw >nul 2>&1
if errorlevel 1 (
    echo Java 21 or newer is required. Please install Java and add it to PATH.
    pause
    exit /b 1
)
if not exist "build\mod-heap-tool\RWJS-ModHeapTool.jar" (
    call gradlew.bat :rwpp-mod-heap-tool:packageTool --console=plain
    if errorlevel 1 (
        pause
        exit /b 1
    )
)
start "" javaw -Xmx768m -Dfile.encoding=UTF-8 -jar "%~dp0build\mod-heap-tool\RWJS-ModHeapTool.jar" %*
