<#
.SYNOPSIS
    把 packaging/icon.svg 转成 packaging/icon.ico（多尺寸）。

.DESCRIPTION
    本机没有 ImageMagick / Inkscape / rsvg-convert，所以用系统自带的两样东西：
      1. Edge headless 把 SVG 渲染成 256x256 透明 PNG（真正的 SVG 渲染，不是手绘近似）
      2. .NET System.Drawing 降采样出各档尺寸，再按 ICO 容器格式打包（Vista+ 支持 PNG 条目）

    产物入库（icon.ico 是二进制资源），所以构建不依赖本脚本；只在改图标时重跑：
        pwsh packaging/make-icon.ps1
    换图标路径见到 build.gradle.kts 的 -Phermes.icon。

.NOTES
    ICO 尺寸覆盖 Windows 会挑的那几档：16/24/32/48/64/128/256。
#>
[CmdletBinding()]
param(
    [string]$Svg = (Join-Path $PSScriptRoot 'icon.svg'),
    [string]$Out = (Join-Path $PSScriptRoot 'icon.ico'),
    # 窗口/托盘图标（Compose 资源，构建时打进 App.jar）
    [string]$AppPng = (Join-Path $PSScriptRoot '..\composeApp\src\commonMain\composeResources\drawable\icon.png'),
    [string]$Edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
    [int[]]$Sizes = @(16, 24, 32, 48, 64, 128, 256)
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if (-not (Test-Path $Svg)) { throw "找不到 SVG：$Svg" }
if (-not (Test-Path $Edge)) { throw "找不到 Edge：$Edge（用它做 SVG 渲染）" }

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("hermes-icon-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
$masterPng = Join-Path $work 'icon-256.png'
$profile = Join-Path $work 'edge-profile'

try {
    $svgUri = ([System.Uri](Resolve-Path $Svg).Path).AbsoluteUri
    # --headless=new + 显式透明背景：老版 headless 的 --screenshot 会铺白底，图标带白角很难看。
    $edgeArgs = @(
        '--headless=new',
        '--disable-gpu',
        '--hide-scrollbars',
        '--force-device-scale-factor=1',
        '--default-background-color=00000000',
        '--window-size=256,256',
        "--user-data-dir=$profile",
        "--screenshot=$masterPng",
        $svgUri
    )
    $proc = Start-Process -FilePath $Edge -ArgumentList $edgeArgs -Wait -PassThru -NoNewWindow
    if (-not (Test-Path $masterPng)) { throw "Edge 渲染失败（exit=$($proc.ExitCode)），没有生成 $masterPng" }

    $master = [System.Drawing.Bitmap]::FromFile($masterPng)
    if ($master.Width -ne 256 -or $master.Height -ne 256) {
        throw "渲染结果尺寸不对：$($master.Width)x$($master.Height)（期望 256x256）"
    }

    # 窗口/托盘图标：同一份渲染结果，构建时作为 Compose 资源打进 App.jar。
    $appPngDir = Split-Path -Parent $AppPng
    if (-not (Test-Path $appPngDir)) { New-Item -ItemType Directory -Path $appPngDir -Force | Out-Null }
    $master.Save($AppPng, [System.Drawing.Imaging.ImageFormat]::Png)

    $pngs = New-Object System.Collections.Generic.List[byte[]]
    foreach ($size in $Sizes) {
        $bmp = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $g.Clear([System.Drawing.Color]::Transparent)
        $g.DrawImage($master, (New-Object System.Drawing.Rectangle 0, 0, $size, $size))
        $g.Dispose()
        $stream = New-Object System.IO.MemoryStream
        $bmp.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
        $pngs.Add($stream.ToArray())
        $stream.Dispose()
        $bmp.Dispose()
    }
    $master.Dispose()

    # ICONDIR + ICONDIRENTRY[] + 各 PNG 数据（宽/高写 0 表示 256）。
    $buffer = New-Object System.IO.MemoryStream
    $writer = New-Object System.IO.BinaryWriter $buffer
    $writer.Write([UInt16]0)                 # reserved
    $writer.Write([UInt16]1)                 # type = icon
    $writer.Write([UInt16]$pngs.Count)
    $offset = 6 + 16 * $pngs.Count
    for ($i = 0; $i -lt $pngs.Count; $i++) {
        $size = $Sizes[$i]
        $dim = if ($size -ge 256) { 0 } else { $size }
        $writer.Write([byte]$dim)            # width
        $writer.Write([byte]$dim)            # height
        $writer.Write([byte]0)               # palette colours
        $writer.Write([byte]0)               # reserved
        $writer.Write([UInt16]1)             # colour planes
        $writer.Write([UInt16]32)            # bits per pixel
        $writer.Write([UInt32]$pngs[$i].Length)
        $writer.Write([UInt32]$offset)
        $offset += $pngs[$i].Length
    }
    foreach ($png in $pngs) { $writer.Write($png) }
    $writer.Flush()
    [System.IO.File]::WriteAllBytes($Out, $buffer.ToArray())
    $writer.Dispose()

    Write-Host ("icon.ico 已生成：{0}（{1} 档：{2}，{3:N0} 字节）" -f `
        $Out, $Sizes.Count, ($Sizes -join '/'), (Get-Item $Out).Length)
    Write-Host ("窗口图标已生成：{0}（256x256）" -f $AppPng)
}
finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
