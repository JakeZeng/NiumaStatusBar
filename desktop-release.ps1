# 用法：
#   . .\env-desktop-windows.ps1                # 先加载 Rust / Node / pnpm 到 PATH
#   .\desktop-release.ps1                      # 用 package.json 现有版本号构建
#   .\desktop-release.ps1 -Version 0.1.56      # 先把 package.json / tauri.conf.json 的
#                                              # version 同步到 0.1.56，再构建
#   .\desktop-release.ps1 -SkipLint            # 跳过 cargo fmt --check + clippy
#   .\desktop-release.ps1 -BundleOnly          # 跳过 lint 之外的 dev gate，只产出 bundle
#   .\desktop-release.ps1 -BundleFormat msi    # 只构建 MSI（默认 msi+nsis）
#
# 桌面端发布构建脚本（Windows）。对齐 .github/workflows/release.yml 的
# publish job 在 windows-latest runner 上的行为：
#   - 同步 version 到 package.json / src-tauri/tauri.conf.json
#   - pnpm install --frozen-lockfile
#   - pnpm tauri build
#   - 列出 src-tauri/target/<triple>/release/bundle/{msi,nsis} 下的产物
#   - 算 SHA256 写到 release/<version>/SHA256SUMS.txt
#
# Linux/macOS 桌面端不在本脚本范围 — 那两个 OS 由 GitHub Actions 的
# ubuntu-22.04 / macos-latest runner 跨平台构建。本地 Windows 发布的
# 产物（.msi / -Setup.exe）跟 CI 完全一致，可以直接做内部分发测试。

[CmdletBinding()]
param(
    [string]$Version,
    [switch]$SkipLint,
    [string[]]$BundleFormat = @('msi', 'nsis'),
    [switch]$NoSha256
)

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

# --- 加载 env --------------------------------------------------------------
. (Join-Path $ScriptDir 'env-desktop-windows.ps1')

# --- 解析目标 triple / 格式 -----------------------------------------------
# tauri.conf.json 的 bundle.targets = "all"，按 tauri-bundler 文档，Windows
# 上的 "all" = msi + nsis。--bundles 参数会传给 tauri build 当作白名单。
if (-not $BundleFormat -or $BundleFormat.Count -eq 0) {
    $BundleFormat = @('msi', 'nsis')
}
$BundleArg = $BundleFormat -join ','

# --- 版本号同步 -----------------------------------------------------------
# 与 release.yml 的 'Sync version from git tag' 步骤等价（本地手动指定版本）。
$PackageJson = Join-Path $ScriptDir 'package.json'
$TauriConf = Join-Path $ScriptDir 'src-tauri\tauri.conf.json'

# --- BOM 自检 ------------------------------------------------------------
# PowerShell 5.1 的 Set-Content -Encoding utf8 会写 BOM，Vite/jiti 的
# PostCSS config 搜索链路里会把 package.json 当 JSON 读，BOM 会让
# JSON.parse 抛 "Unexpected token ﻿{..." SyntaxError。每次发布前
# 防御性地剥 package.json / tauri.conf.json 顶层的 BOM，不改内容。
function Remove-BomIfPresent {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return }
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        Write-Host "  strip BOM: $Path" -ForegroundColor DarkGray
        $content = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($Path, $content, $utf8NoBom)
    }
}
Write-Host "`n[pre] BOM self-check" -ForegroundColor Cyan
Remove-BomIfPresent -Path $PackageJson
Remove-BomIfPresent -Path $TauriConf

if ($Version) {
    if ($Version -notmatch '^\d+\.\d+\.\d+(-\w+)?$') {
        throw "Version '$Version' is not semver (expected X.Y.Z or X.Y.Z-rc1)"
    }
    Write-Host "Bumping version to $Version" -ForegroundColor Cyan

    $pkg = Get-Content $PackageJson -Raw | ConvertFrom-Json
    $pkg.version = $Version
    # PowerShell 5.1 的 Set-Content -Encoding utf8 默认会写 BOM，Vite/jiti
    # 的 PostCSS config 搜索链路里会把 package.json 当 JSON 读，BOM 会让
    # JSON.parse 抛 "Unexpected token" SyntaxError。显式用 UTF8Encoding($false)。
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($PackageJson, ($pkg | ConvertTo-Json -Depth 10), $utf8NoBom)

    # tauri.conf.json 顶层是 JSON object with `version` field — 用 raw regex
    # 避免引入额外的 jq 依赖（repo 没有 jq.json）。
    $confText = Get-Content $TauriConf -Raw
    $confText = [regex]::Replace($confText, '"version"\s*:\s*"[^"]*"', "`"version`": `"$Version`"", 1)
    [System.IO.File]::WriteAllText($TauriConf, $confText, $utf8NoBom)

    Write-Host '--- synced version ---' -ForegroundColor DarkGray
    Select-String -Path $PackageJson, $TauriConf -Pattern '"version"\s*:\s*"[^"]*"' | ForEach-Object {
        Write-Host "  $($_.Path):$($_.LineNumber): $($_.Line.Trim())"
    }
}

# 读最终版本号（给产物归档用）。
$FinalVersion = (Get-Content $PackageJson -Raw | ConvertFrom-Json).version
Write-Host "Building NiumaStatusBar v$FinalVersion" -ForegroundColor Green

# --- 1. 前端依赖 ----------------------------------------------------------
Write-Host "`n[1/5] pnpm install --frozen-lockfile" -ForegroundColor Cyan
& pnpm install --frozen-lockfile
if ($LASTEXITCODE -ne 0) { throw "pnpm install failed (exit $LASTEXITCODE)" }

# --- 2. Lint gate (optional) ---------------------------------------------
if (-not $SkipLint) {
    Push-Location (Join-Path $ScriptDir 'src-tauri')

    # 临时把 ErrorActionPreference 调成 Continue，避免 cargo 写 stderr
    # （例如 "    Checking windows-sys v0.61.2"）触发 NativeCommandError
    # 让 PowerShell 误以为失败。整个 lint block 用 try/finally 保证
    # 后续代码不受影响。
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'

    try {
        Write-Host "`n[2/5] cargo fmt --check" -ForegroundColor Cyan
        $fmtOutput = & cargo fmt --all -- --check 2>&1
        $fmtExit = $LASTEXITCODE
        if ($fmtExit -ne 0) {
            Write-Host $fmtOutput
            throw "cargo fmt --check failed (exit $fmtExit). Run `cargo fmt` in src-tauri/ and re-run this script."
        }

        Write-Host "`n[2/5] cargo clippy (informational, non-fatal)" -ForegroundColor Cyan
        # clippy 经常在新 toolchain 上冒出新 warning，发版脚本不应阻塞。
        # 失败也不 throw，只记录到 release/<ver>/clippy.log 供事后看。
        $ClippyLog = Join-Path $ScriptDir "release\$FinalVersion\clippy.log"
        New-Item -ItemType Directory -Path (Split-Path $ClippyLog) -Force | Out-Null
        $clippyOutput = & cargo clippy --all-targets --no-deps -- -D warnings 2>&1 | Tee-Object -FilePath $ClippyLog
        $clippyExit = $LASTEXITCODE
        if ($clippyExit -ne 0) {
            Write-Host "  WARN: clippy exit $clippyExit — see $ClippyLog" -ForegroundColor Yellow
        } else {
            Write-Host "  clippy clean" -ForegroundColor Green
        }
    } finally {
        $ErrorActionPreference = $prevEAP
        Pop-Location
    }
} else {
    Write-Host "`n[2/5] Lint skipped (-SkipLint)" -ForegroundColor DarkGray
}

# --- 3. 前端类型检查 + Vite 构建 ----------------------------------------
Write-Host "`n[3/5] pnpm build (tsc + vite build)" -ForegroundColor Cyan
# PowerShell 5.1 会把 rollup 的"Generated an empty chunk"等 stderr
# 行当 ErrorRecord + 终止错误抛，但实际 exit code 是 0。临时切到
# Continue 跳过这个误判。
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & pnpm build 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "pnpm build failed (exit $LASTEXITCODE)" }
} finally {
    $ErrorActionPreference = $prevEAP
}

# --- 4. Tauri bundle ----------------------------------------------------
Write-Host "`n[4/5] pnpm tauri build --bundles $BundleArg" -ForegroundColor Cyan
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & pnpm tauri build --bundles $BundleArg 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "pnpm tauri build failed (exit $LASTEXITCODE)" }
} finally {
    $ErrorActionPreference = $prevEAP
}

# --- 5. 收集产物 + SHA256 -----------------------------------------------
Write-Host "`n[5/5] Collecting artifacts" -ForegroundColor Cyan
# tauri-bundler 写的目录结构：src-tauri/target/<triple>/release/bundle/<fmt>/
$BundleRoot = Join-Path $ScriptDir "src-tauri\target\$env:PROCESSOR_ARCHITECTURE"
# Windows 上 tauri-bundler 用 x86_64-pc-windows-msvc 这个 triple 目录名。
$BundleRootWin = Join-Path $ScriptDir 'src-tauri\target\x86_64-pc-windows-msvc\release\bundle'
$BundleRootGeneric = Join-Path $ScriptDir 'src-tauri\target\release\bundle'

$BundleDir = $null
foreach ($cand in @($BundleRootWin, $BundleRootGeneric)) {
    if (Test-Path $cand) { $BundleDir = $cand; break }
}
if (-not $BundleDir) {
    throw "Bundle directory not found under src-tauri\target. Check the build output above."
}

$ReleaseDir = Join-Path $ScriptDir "release\$FinalVersion"
New-Item -ItemType Directory -Path $ReleaseDir -Force | Out-Null

$Artifacts = @()
foreach ($fmt in $BundleFormat) {
    $fmtDir = Join-Path $BundleDir $fmt
    if (-not (Test-Path $fmtDir)) {
        Write-Host "  WARN: $fmtDir not found (format '$fmt' produced nothing)" -ForegroundColor Yellow
        continue
    }
    Get-ChildItem $fmtDir -File -Recurse | ForEach-Object {
        $dest = Join-Path $ReleaseDir $_.Name
        Copy-Item $_.FullName $dest -Force
        $Artifacts += $dest
    }
}

if ($Artifacts.Count -eq 0) {
    throw "No artifacts collected from $BundleDir. Check tauri build output."
}

Write-Host "`nArtifacts:" -ForegroundColor Green
foreach ($a in $Artifacts) {
    $size = "{0:N2} MB" -f ((Get-Item $a).Length / 1MB)
    Write-Host "  $a ($size)"
}

# SHA256SUMS
if (-not $NoSha256) {
    $SumsFile = Join-Path $ReleaseDir 'SHA256SUMS.txt'
    Remove-Item $SumsFile -ErrorAction SilentlyContinue
    foreach ($a in $Artifacts) {
        $h = (Get-FileHash $a -Algorithm SHA256).Hash.ToLower()
        $relName = Split-Path $a -Leaf
        "$h  $relName" | Add-Content $SumsFile -Encoding utf8
    }
    Write-Host "`nSHA256SUMS -> $SumsFile" -ForegroundColor Cyan
    Get-Content $SumsFile | ForEach-Object { Write-Host "  $_" }
}

Write-Host "`nDone. Artifacts staged at: $ReleaseDir" -ForegroundColor Green
Write-Host 'Next: push the tag (e.g. `git tag v0.1.56`) to let release.yml build the rest (macOS + Linux).' -ForegroundColor DarkGray
