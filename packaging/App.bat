@echo off
chcp 65001 >nul
rem ===========================================================================
rem  Hermes desktop client - launcher
rem  ---------------------------------------------------------------------------
rem  JVM args live in exactly ONE place: app\hermes-desktop-compose.cfg -- the same file the
rem  native launcher (hermes-desktop-compose.exe) reads. Do not duplicate options here.
rem    [Application]  app.runtime / app.classpath / app.mainclass
rem    [JavaOptions]  java-options=... (one per line)
rem  $ROOTDIR / $APPDIR in values are expanded to this folder.
rem  Optional user overrides: config\jvm.args.local (one arg per line, builds
rem  never touch it, appended last so it wins on duplicate -D keys).
rem
rem  Usage: App.bat            javaw + start, returns immediately (no console)
rem         App.bat --console  java, runs in this console (troubleshooting)
rem
rem  Save as UTF-8 (no BOM) + CRLF. Comments must stay ASCII-only: cmd locates
rem  batch lines by byte offset, so multi-byte chars break long lines.
rem ===========================================================================

setlocal enabledelayedexpansion

set "ROOT=%~dp0"
if "!ROOT:~-1!"=="\" set "ROOT=!ROOT:~0,-1!"
set "CFG=%ROOT%\app\hermes-desktop-compose.cfg"
set "CONSOLE=0"
if /i "%~1"=="--console" set "CONSOLE=1"

if not exist "%CFG%" (
  echo [错误] 找不到启动配置：
  echo        %CFG%
  echo        请确认 app\ 目录完整（应含 hermes-desktop-compose.cfg），或重新解压安装包。
  pause
  exit /b 1
)

set "RUNTIME=%ROOT%\rt"
set "SECTION="
set "CP="
set "MAIN="
set "OPTS="

for /f "usebackq eol=# delims=" %%a in ("%CFG%") do (
    set "line=%%a"
    if "!line!"=="[JavaOptions]" (
        set "SECTION=java"
    ) else if "!line!"=="[Application]" (
        set "SECTION=app"
    ) else if "!SECTION!"=="app" (
        if "!line:~0,12!"=="app.runtime=" (
            set "v=!line:~12!"
            set "v=!v:$ROOTDIR=%ROOT%!"
            set "RUNTIME=!v!"
        ) else if "!line:~0,14!"=="app.mainclass=" (
            set "MAIN=!line:~14!"
        ) else if "!line:~0,14!"=="app.classpath=" (
            set "v=!line:~14!"
            set "v=!v:$ROOTDIR=%ROOT%!"
            set "v=!v:$APPDIR=%ROOT%\app!"
            if defined CP (set "CP=!CP!;!v!") else (set "CP=!v!")
        )
    ) else if "!SECTION!"=="java" (
        if "!line:~0,13!"=="java-options=" (
            set "v=!line:~13!"
            set "v=!v:$ROOTDIR=%ROOT%!"
            set "v=!v:$APPDIR=%ROOT%\app!"
            set "OPTS=!OPTS! "!v!""
        )
    )
)

set "LOCAL="
if exist "%ROOT%\config\jvm.args.local" (
    for /f "usebackq eol=# delims=" %%a in ("%ROOT%\config\jvm.args.local") do set "LOCAL=!LOCAL! "%%a""
)

if not defined CP (
  echo [错误] app\hermes-desktop-compose.cfg 缺少 app.classpath。
  pause
  exit /b 1
)

set "JAVA=!RUNTIME!\bin\javaw.exe"
if "%CONSOLE%"=="1" set "JAVA=!RUNTIME!\bin\java.exe"
if not exist "!JAVA!" (
  echo [错误] 找不到 Java 运行时：
  echo        !JAVA!
  echo        请确认 rt\ 目录完整（应含 bin\java.exe），或重新解压安装包。
  pause
  exit /b 1
)

if not exist "%ROOT%\logs" mkdir "%ROOT%\logs" 2>nul

if "%CONSOLE%"=="1" (
    echo [App] 控制台模式启动，关闭窗口即退出应用。
    "!JAVA!" !OPTS! !LOCAL! -cp "!CP!" !MAIN!
    if errorlevel 1 (
        echo.
        echo [错误] 程序异常退出，退出码 !errorlevel!。
        pause
    )
    endlocal
    exit /b 0
)

start "" "!JAVA!" !OPTS! !LOCAL! -cp "!CP!" !MAIN!

endlocal