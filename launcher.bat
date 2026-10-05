@echo off
setlocal enabledelayedexpansion

set DestPath=%~dp0
cd /d "%DestPath%"
if not exist logs mkdir logs
set DestExt=*.jar*

set /a count=0
set /a lastName=0

for /f "usebackq delims=" %%a in (`dir /b/a-d/s  "%DestPath%\%DestExt%"`) do (
set "name=%%~na"
set "c=!name:~0,18!"
set "c1=!name:~0,12!"
set "c2=!name:~0,4!"
if "!c2!"=="RWJS" (
set /a count+=1
set "lastName=%%a"
)
if "!c!"=="RWPP-multiplatform"  (
set /a count+=1
set "lastName=%%a"
)

if "!c1!"=="RWPP-windows"  (
set /a count+=1
set "lastName=%%a"
)

)

if %count% EQU 0 (
	echo "No RWPP-multiplatform file exists"
	pause
	exit
)

if %count% GTR 1 (
	echo "Multiple RWPP-multiplatform file exists"
	pause
	exit
)

if %count% EQU 1 (
	java -D"java.net.preferIPv4Stack=true" -Xmx2000M -XX:ErrorFile=logs/hs_err_pid%%p.log -D"file.encoding=UTF-8" -D"prism.allowhidpi=false" -D"java.library.path=." -cp "!lastName!;generated_lib/*;extension/*;libs/*" io.github.rwpp.desktop.MainKt
)

pause
