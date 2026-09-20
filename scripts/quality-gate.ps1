# ==============================================================================
#  CampusTrade 质量门禁：后端测试 + 前端静态分析与测试，任一失败即以非 0 退出
#  用法：powershell -File scripts/quality-gate.ps1
#  说明：本文件为 UTF-8 with BOM，请勿转成其它编码（PowerShell 5.1 依赖 BOM 正确读取中文）
# ==============================================================================
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$rootDir = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $rootDir "backend"
$frontendDir = Join-Path $rootDir "frontend"

# 工具链解析：环境变量优先 → 本机常见安装位置 → PATH，全部失败则明确报错
function Resolve-Tool {
    param([string]$Name, [string[]]$Candidates, [string]$EnvVar)

    if ($EnvVar -and $env:JAVA_HOME -and $Name -eq "java") { return $null }   # java 由 JAVA_HOME 提供
    foreach ($c in $Candidates) { if (Test-Path $c) { return $c } }
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$javaHome = if ($env:JAVA_HOME -and (Test-Path $env:JAVA_HOME)) { $env:JAVA_HOME }
            elseif (Test-Path "D:\yp3\.tools\jdk21") { "D:\yp3\.tools\jdk21" }
            else { $null }

$mvnCmd = Resolve-Tool -Name "mvn" -Candidates @("D:\yp3\.tools\maven\bin\mvn.cmd") -EnvVar "MAVEN_HOME"
$flutterCmd = Resolve-Tool -Name "flutter" -Candidates @("D:\flutter_sdk\flutter\bin\flutter.bat", "C:\flutter\bin\flutter.bat") -EnvVar "FLUTTER_ROOT"

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host " CampusTrade 质量门禁" -ForegroundColor Cyan
Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host " JAVA_HOME : $javaHome"
Write-Host " Maven     : $mvnCmd"
Write-Host " Flutter   : $flutterCmd"
Write-Host ""

$failures = @()

if (-not $javaHome) { $failures += "未找到 JDK 21：请设置 JAVA_HOME 或安装 JDK 21" }
if (-not $mvnCmd)   { $failures += "未找到 Maven：请设置 MAVEN_HOME 或安装 Maven" }
if (-not $flutterCmd) { $failures += "未找到 Flutter SDK：请安装或把 flutter 加入 PATH" }

if ($failures.Count -gt 0) {
    Write-Host "[失败] 环境检查未通过：" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}

# ---------------------------------------------------------------------------
# 1. 后端测试
# ---------------------------------------------------------------------------
Write-Host "[1/3] 后端测试 (mvn -B test)..." -ForegroundColor Cyan
Push-Location $backendDir
$env:JAVA_HOME = $javaHome
& $mvnCmd -B test
$backendCode = $LASTEXITCODE
Pop-Location
if ($backendCode -ne 0) {
    Write-Host "[失败] 后端测试未通过（退出码 $backendCode）" -ForegroundColor Red
} else {
    Write-Host "[通过] 后端测试全部通过" -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# 2. 前端静态分析
# ---------------------------------------------------------------------------
Write-Host "`n[2/3] 前端静态分析 (flutter analyze)..." -ForegroundColor Cyan
Push-Location $frontendDir
& $flutterCmd analyze
$analyzeCode = $LASTEXITCODE
Pop-Location
if ($analyzeCode -ne 0) {
    Write-Host "[失败] flutter analyze 存在 issue（退出码 $analyzeCode）" -ForegroundColor Red
} else {
    Write-Host "[通过] flutter analyze 无 issue" -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# 3. 前端测试
# ---------------------------------------------------------------------------
Write-Host "`n[3/3] 前端测试 (flutter test)..." -ForegroundColor Cyan
Push-Location $frontendDir
& $flutterCmd test
$flutterTestCode = $LASTEXITCODE
Pop-Location
if ($flutterTestCode -ne 0) {
    Write-Host "[失败] 前端测试未通过（退出码 $flutterTestCode）" -ForegroundColor Red
} else {
    Write-Host "[通过] 前端测试全部通过" -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# 汇总：三者全绿才返回 0
# ---------------------------------------------------------------------------
Write-Host "`n======================================================================" -ForegroundColor Cyan
if ($backendCode -ne 0 -or $analyzeCode -ne 0 -or $flutterTestCode -ne 0) {
    Write-Host " 质量门禁未通过：后端测试=$backendCode 前端分析=$analyzeCode 前端测试=$flutterTestCode" -ForegroundColor Red
    Write-Host "======================================================================" -ForegroundColor Cyan
    exit 1
}
Write-Host " 质量门禁通过：后端测试 / 前端分析 / 前端测试 全部成功" -ForegroundColor Green
Write-Host "======================================================================" -ForegroundColor Cyan
exit 0
