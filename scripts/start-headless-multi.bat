@echo off
setlocal enabledelayedexpansion

REM Single-JVM multi-instance headless launcher for ostinato-tenorclef (Windows)
REM Defaults to G1GC with string deduplication, 2 GiB max heap for multi-bot runs, bounded Netty buffers, chunk radius cap 6, and 150ms login stagger.
REM Pass --zgc to enable Generational ZGC, --accounts-file <path> for offline/online accounts, --control-port <port> for 127.0.0.1 HTTP/WS fleet control, or --stagger-ms <ms> to customize login stagger.
REM Override heap sizing with HEAP_MAX / HEAP_INIT environment variables (defaults: HEAP_MAX=2g, HEAP_INIT=256m).

set "ROOT_DIR=%~dp0.."
if "%HEAP_MAX%"=="" set "HEAP_MAX=2g"
if "%HEAP_INIT%"=="" set "HEAP_INIT=256m"
set "USE_ZGC=0"
set "MULTI_ARGS="

:parse_args
if "%~1"=="" goto done_args
if "%~1"=="--zgc" (
    set "USE_ZGC=1"
) else (
    if "!MULTI_ARGS!"=="" (
        set "MULTI_ARGS=%~1"
    ) else (
        set "MULTI_ARGS=!MULTI_ARGS! %~1"
    )
)
shift
goto parse_args
:done_args

if "%USE_ZGC%"=="1" (
    set "GC_FLAGS=-XX:+UseZGC -XX:+ZGenerational"
) else (
    set "GC_FLAGS=-XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+UseStringDeduplication"
)

if "%MULTI_ARGS%"=="" set "MULTI_ARGS=--bots 4 --host 127.0.0.1 --port 25565 --seconds 600 --stagger-ms 150"

if not "%HEADLESS_CP%"=="" (
    java -Xms%HEAP_INIT% -Xmx%HEAP_MAX% %GC_FLAGS% ^
        -XX:MaxDirectMemorySize=512m ^
        -Dio.netty.allocator.numHeapArenas=2 ^
        -Dio.netty.allocator.numDirectArenas=2 ^
        -Dio.netty.allocator.maxOrder=9 ^
        -Djava.awt.headless=true ^
        -Dostinato.runMode=headless ^
        -Dostinato.headless.chunkRadius=6 ^
        --enable-native-access=ALL-UNNAMED ^
        -cp "%HEADLESS_CP%" baritone.headless.HeadlessMultiLauncher %MULTI_ARGS%
    exit /b %ERRORLEVEL%
)

pushd "%ROOT_DIR%\ostinato"
call gradlew.bat --no-daemon runHeadlessMulti ^
    "-PmultiXmx=%HEAP_MAX%" ^
    "-PmultiArgs=%MULTI_ARGS%" ^
    "-Dostinato.runMode=headless" ^
    "-Dostinato.headless.chunkRadius=6"
set "RC=%ERRORLEVEL%"
popd
exit /b %RC%
