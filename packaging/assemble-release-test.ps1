<#
.SYNOPSIS
    组装/更新 release-test/ —— 发版前的本地工作区（客户端壳 + hermes payload）。

.DESCRIPTION
    release-test/ 的定位：
      * **发版前的本地组装与验证目录**（已 gitignore）：壳和 payload 都在这里拼好，
        人工跑一遍确认没问题，再去触发 desktop-release 发版
      * 里面的 `hermes/` 同时就是**开发时的后端**：`HDC_HOME` 指向 release-test 后，
        `backend.local.hermes_dir=hermes` 解析到 `release-test/hermes`

    两件事都做，但各自可跳过：
      1. 壳：`./gradlew :composeApp:syncReleaseTest`（启动器 + App.jar + lib + rt + config）
      2. payload：从 Shinglem/hermes-bundle 的 Release 取，解压到 `release-test/hermes`

    启动器与运行时**不重新生成**：`packaging/launcher/hermes-desktop-compose.exe` 与 `release-test/rt` 就位时
    Gradle 自己跳过 jpackage / jlink。第一次什么都没有时才现场生成。

.EXAMPLE
    pwsh packaging/assemble-release-test.ps1
    # 更新壳；payload 缺了才下载

.EXAMPLE
    pwsh packaging/assemble-release-test.ps1 -RefreshPayload

.EXAMPLE
    pwsh packaging/assemble-release-test.ps1 -ShellOnly
    # 只重编壳，payload 原样保留（改客户端代码时用）

.NOTES
    需要在跑着客户端时先关掉：脚本会检测 release-test 被占用并拒绝写入。
    本机访问 GitHub 需代理时先设 $env:HTTPS_PROXY。
#>
[CmdletBinding()]
param(
    # 从哪个 payload Release 取；留空 = hermes-bundle 的 latest
    [string]$PayloadTag = '',
    [string]$PayloadRepo = 'Shinglem/hermes-bundle',
    # 只重编客户端壳，不动 payload
    [switch]$ShellOnly,
    # 强制重新下载 payload（即使已经存在）
    [switch]$RefreshPayload,
    # 跳过 Gradle（只补 payload）
    [switch]$PayloadOnly
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$target = Join-Path $root 'release-test'

function Assert-NotBusy {
    if (-not (Test-Path $target)) { return }
    $busy = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like "*$target*" -and $_.ProcessId -ne $PID }
    if ($busy) {
        throw ("release-test 正在被占用（pid: $($busy.ProcessId -join ', ')）。" +
               '先关掉客户端窗口（会一并停掉后端），再重跑。')
    }
}

function Get-Curl {
    $c = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    $c = Get-Command curl -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    throw '需要 curl（Windows 10+ 自带）'
}

function Get-SevenZip {
    $installed = @('C:\Program Files\7-Zip\7z.exe', 'C:\Program Files (x86)\7-Zip\7z.exe') |
        Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($installed) { return $installed }
    $onPath = (Get-Command 7zr.exe, 7z.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    if ($onPath) { return $onPath }
    # 零依赖兜底：7-Zip Reduced，600 KB，只支持 .7z，正好够用
    $cacheDir = Join-Path $env:LOCALAPPDATA 'hermes-assemble'
    $cached = Join-Path $cacheDir '7zr.exe'
    if (-not (Test-Path $cached)) {
        Write-Host '本机没有 7-Zip，下载 7zr.exe（约 600 KB）…'
        New-Item -ItemType Directory -Force $cacheDir | Out-Null
        & (Get-Curl) -fsSL --retry 3 -o $cached 'https://www.7-zip.org/a/7zr.exe'
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $cached)) {
            throw '无法下载 7zr.exe；请装 7-Zip（winget install 7zip.7zip）后重跑'
        }
    }
    return $cached
}

# ============================ 1. 客户端壳 ============================
if (-not $PayloadOnly) {
    Assert-NotBusy
    New-Item -ItemType Directory -Force $target | Out-Null

    # 启动器：有入库的就不重新 jpackage（不同 JDK 的模板不同，见 build.gradle.kts）
    $launcherName = 'hermes-desktop-compose.exe'
    $launcher = Join-Path $root "packaging\launcher\$launcherName"
    if (-not (Test-Path $launcher)) {
        Write-Warning ("packaging/launcher/$launcherName 不存在，Gradle 会用本机 JDK 的 jpackage 现场生成" +
                       '（与工具链 Release 里的产物可能不同字节）。要一致就先从 toolchain-v<N> 取：')
        Write-Host "  gh release download toolchain-v<N> --pattern launcher-windows-x64.zip --dir C:\work"
    }

    # rt：已有就不重新 jlink（传 hermes.rt.dir 指向它，且它没有 jmods）
    $gradleArgs = @(':composeApp:syncReleaseTest')
    $rtDir = Join-Path $target 'rt'
    if (Test-Path (Join-Path $rtDir 'bin\java.exe')) {
        $gradleArgs += "-Phermes.rt.dir=$rtDir"
        # 必须把 hermes.jmods.dir 压成空：本机 local.properties 里那一行优先级更高，
        # 留着它 jlink 就会重新跑一遍并覆盖 rt/（结果虽然一样，但白等一分钟）。
        # 空字符串在 build.gradle.kts 的 pick() 里被 takeIf{isNotBlank()} 过滤成 null。
        $gradleArgs += '-Phermes.jmods.dir='
        Write-Host "复用已有运行时：$rtDir"
    } else {
        Write-Warning 'release-test/rt 缺失，将由 Gradle 现场 jlink（需 local.properties 的 hermes.jmods.dir）'
    }

    Write-Host ''
    Write-Host "gradlew $($gradleArgs -join ' ')"
    Push-Location $root
    try {
        & .\gradlew.bat @gradleArgs --console=plain
        if ($LASTEXITCODE -ne 0) { throw "gradle 失败，exit=$LASTEXITCODE" }
    } finally { Pop-Location }

    foreach ($p in $launcherName, 'app\hermes-desktop-compose.cfg', 'bin\App.jar', 'lib', 'rt\bin\java.exe', 'config\config.json') {
        if (-not (Test-Path (Join-Path $target $p))) { throw "组装后缺少 release-test\$p" }
    }
    Write-Host '客户端壳就绪' -ForegroundColor Green
}

if ($ShellOnly) {
    Write-Host ''
    Write-Host '已跳过 payload。开发运行：' -ForegroundColor Cyan
    Write-Host "  `$env:HDC_HOME = '$target'"
    Write-Host '  .\gradlew.bat :composeApp:run'
    exit 0
}

# ============================ 2. payload ============================
$payloadDir = Join-Path $target 'hermes'
$sealed = Test-Path (Join-Path $payloadDir 'manifest.json')
if ($sealed -and -not $RefreshPayload) {
    $mb = [math]::Round((Get-ChildItem $payloadDir -Recurse -File -Force | Measure-Object Length -Sum).Sum / 1MB)
    Write-Host "payload 已存在（$mb MB），跳过下载；要强制更新加 -RefreshPayload" -ForegroundColor Green
} else {
    Assert-NotBusy
    $curl = Get-Curl
    $headers = @{ Accept = 'application/vnd.github+json' }

    # payload 仓库是 public，不需要 token（runner / 本机都一样）
    if (-not $PayloadTag) {
        $latest = Invoke-RestMethod -Uri "https://api.github.com/repos/$PayloadRepo/releases/latest" `
            -Headers $headers -TimeoutSec 60
        $PayloadTag = $latest.tag_name
    }
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$PayloadRepo/releases/tags/$PayloadTag" `
        -Headers $headers -TimeoutSec 60
    $asset = $release.assets | Where-Object { $_.name -like '*.7z.001' } | Select-Object -First 1
    if (-not $asset) { throw "$PayloadRepo@$PayloadTag 里没有 *.7z.001" }
    Write-Host ("payload: {0}@{1}  {2}  {3:N0} MB" -f $PayloadRepo, $PayloadTag, $asset.name, ($asset.size / 1MB))

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("hermes-payload-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
    New-Item -ItemType Directory -Force $tmp | Out-Null
    $archive = Join-Path $tmp $asset.name
    try {
        Write-Host '下载中…'
        $sw = [Diagnostics.Stopwatch]::StartNew()
        & $curl -fL --retry 3 --retry-delay 2 -sS -o $archive $asset.browser_download_url
        if ($LASTEXITCODE -ne 0) { throw "下载失败，exit=$LASTEXITCODE" }
        $sw.Stop()
        $got = (Get-Item $archive).Length
        if ($got -ne $asset.size) { throw "大小不符：$got / 期望 $($asset.size)" }
        Write-Host ("完成 {0:N0} MB / {1:N1} 分" -f ($got / 1MB), $sw.Elapsed.TotalMinutes)

        $sums = $release.assets | Where-Object { $_.name -eq 'SHA256SUMS.txt' } | Select-Object -First 1
        if ($sums) {
            $sumsFile = Join-Path $tmp 'SHA256SUMS.txt'
            & $curl -fsSL -o $sumsFile $sums.browser_download_url
            $expected = (Get-Content $sumsFile | Where-Object { $_ -like "*$($asset.name)" }) -split '\s+' |
                Select-Object -First 1
            $actual = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLower()
            if ($expected -and $actual -ne $expected) { throw "SHA256 不符：$actual / 期望 $expected" }
            Write-Host "SHA256 校验通过：$actual"
        }

        $sevenZip = Get-SevenZip
        Write-Host "解压中（$sevenZip）…"
        $stage = Join-Path $tmp 'stage'
        New-Item -ItemType Directory -Force $stage | Out-Null
        & $sevenZip x $archive "-o$stage" -y | Out-Null
        if ($LASTEXITCODE -gt 1) { throw "解压失败，exit=$LASTEXITCODE" }

        $inner = Join-Path $stage 'payload'
        if (-not (Test-Path (Join-Path $inner 'manifest.json'))) {
            throw "解压结果里没有 payload/manifest.json（$stage）"
        }
        if (Test-Path $payloadDir) { Remove-Item $payloadDir -Recurse -Force }
        Move-Item $inner $payloadDir
        Write-Host 'payload 就位' -ForegroundColor Green
    } finally {
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# ============================ 3. 汇总 ============================
Write-Host ''
Write-Host '=== release-test/ ==='
Get-ChildItem $target -Force | ForEach-Object {
    if ($_.PSIsContainer) {
        $c = Get-ChildItem $_.FullName -Recurse -File -Force -ErrorAction SilentlyContinue |
            Measure-Object Length -Sum
        '{0,-10} {1,8:N0} 文件 {2,10:N1} MB' -f $_.Name, $c.Count, ($c.Sum / 1MB)
    } else {
        '{0,-10} {1,25:N0} B' -f $_.Name, $_.Length
    }
}

$cfgPath = Join-Path $target 'config\config.json'
$cfg = Get-Content $cfgPath -Raw | ConvertFrom-Json
Write-Host ''
Write-Host "backend.mode = $($cfg.backend.mode)"
if ($cfg.backend.mode -eq 'local') {
    $missing = @()
    foreach ($p in 'manifest.json', 'bin\hermes.exe', 'hermes-agent\install-stamp.json',
                   'hermes-agent\hermes_cli\web_dist\index.html') {
        if (-not (Test-Path (Join-Path $payloadDir $p))) { $missing += $p }
    }
    if ($missing) {
        Write-Warning "payload 缺：$($missing -join ', ')（Web UI 或版本信息会不正常）"
    } else {
        Write-Host '  payload 完整（含 install-stamp 与 web_dist）' -ForegroundColor Green
    }
}

Write-Host ''
Write-Host '=== 两种验证方式 ===' -ForegroundColor Cyan
Write-Host '  1) 直接跑成品（与发出去的完全一致）：'
Write-Host "       & '$target\hermes-desktop-compose.exe'"
Write-Host '  2) 开发态跑（改代码即时生效，后端仍用这份 payload）：'
Write-Host "       `$env:HDC_HOME = '$target'"
Write-Host '       .\gradlew.bat :composeApp:run'
Write-Host ''
Write-Host '确认无误后触发发版：Actions → desktop-release → Run workflow（填 desktop_version / hermes_version / toolchain_version）' -ForegroundColor Cyan
