# ==============================================================================
#  CampusTrade 质量门禁：后端测试 + 前端静态分析与测试，任一失败即以非 0 退出
#  用法：powershell -NoProfile -ExecutionPolicy Bypass -File scripts/quality-gate.ps1
#
#  说明：
#   1. 本文件是"测试怎么跑、算不算过"的唯一入口定义：start.ps1 的选项 6 直接调用
#      本脚本并把它的退出码原样透传，不再自己另写一套测试流程；
#   2. 工具链解析统一走 scripts\toolchain.ps1（环境变量 → PATH → 明确报错指引），
#      不写死任何盘符；也可用项目根目录的 .env.tools（git-ignored）持久化工具链位置；
#   3. 每个外部命令都检查退出码：任一步失败 → 提示具体是哪一步 → exit 1；
#   4. 本文件为 UTF-8 with BOM，请勿转成其它编码（PowerShell 5.1 依赖 BOM 正确读取中文）。
# ==============================================================================
[CmdletBinding()]
param(
    # 只跑其中一项（backend / analyze / flutter-test），默认三项全跑。
    # 例：-Only backend
    [string]$Only = ""
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$rootDir = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $rootDir "backend"
$frontendDir = Join-Path $rootDir "frontend"

. (Join-Path $PSScriptRoot "toolchain.ps1")
$toolchainFile = Import-LocalToolchainEnv -RootDir $rootDir

$javaHome = Resolve-JdkHome
$mvnCmd = Resolve-MavenCmd
$flutterCmd = Resolve-FlutterCmd

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host " CampusTrade 质量门禁" -ForegroundColor Cyan
Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host " JAVA_HOME : $(if ($javaHome) { $javaHome } else { '(未找到)' })"
Write-Host " Maven     : $(if ($mvnCmd) { $mvnCmd } else { '(未找到)' })"
Write-Host " Flutter   : $(if ($flutterCmd) { $flutterCmd } else { '(未找到)' })"
if ($toolchainFile) { Write-Host " 本地工具链: $toolchainFile" }
Write-Host ""

# ---------------------------------------------------------------------------
# 0. 环境检查：缺工具直接失败，不做"跳过该项继续跑"的处理
# ---------------------------------------------------------------------------
$envFailures = @()
if (-not $javaHome) { $envFailures += "JDK 21" }
if (-not $mvnCmd) { $envFailures += "Maven" }
if (-not $flutterCmd) { $envFailures += "Flutter SDK" }

if ($envFailures.Count -gt 0) {
    Write-Host "[失败] 环境检查未通过，缺少: $($envFailures -join ', ')" -ForegroundColor Red
    if (-not $javaHome) { Write-ToolchainGuidance -ToolName "JDK 21" -EnvVar "JAVA_HOME" -InstallHint "安装 OpenJDK 21 LTS（或让本机已有的 JDK 21 出现在 PATH 中）" }
    if (-not $mvnCmd) { Write-ToolchainGuidance -ToolName "Maven" -EnvVar "MAVEN_HOME" -InstallHint "安装 Apache Maven 3.9+ 并确保 mvn 在 PATH 中" }
    if (-not $flutterCmd) { Write-ToolchainGuidance -ToolName "Flutter SDK" -EnvVar "FLUTTER_ROOT" -InstallHint "安装 Flutter 3.x 并确保 flutter 在 PATH 中" }
    exit 1
}

$runBackend = ($Only -eq "" -or $Only -eq "backend")
$runAnalyze = ($Only -eq "" -or $Only -eq "analyze")
$runFlutterTest = ($Only -eq "" -or $Only -eq "flutter-test")

$backendCode = 0
$analyzeCode = 0
$flutterTestCode = 0

# ---------------------------------------------------------------------------
# 1. 后端测试
# ---------------------------------------------------------------------------
if ($runBackend) {
    Write-Host "[1/3] 后端测试 (mvn -B test)..." -ForegroundColor Cyan
    Push-Location $backendDir
    try {
        # 注意：这里刻意不加载项目根目录 .env。后端测试的中间件由 Testcontainers 现拉现用，
        # 若把 .env 里的 SPRING_DATA_REDIS_PASSWORD 等导出到进程环境，测试会去给一个
        # "没设口令的临时 Redis"发 AUTH，测试反而会失败。
        $env:JAVA_HOME = $javaHome
        & $mvnCmd -B test | Out-Host
        $backendCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($backendCode -ne 0) {
        Write-Host "[失败] 后端测试未通过（退出码 $backendCode）" -ForegroundColor Red
    } else {
        Write-Host "[通过] 后端测试全部通过" -ForegroundColor Green
    }
}

# ---------------------------------------------------------------------------
# 2. 前端静态分析
# ---------------------------------------------------------------------------
if ($runAnalyze) {
    Write-Host "`n[2/3] 前端静态分析 (flutter analyze)..." -ForegroundColor Cyan
    Push-Location $frontendDir
    try {
        & $flutterCmd analyze | Out-Host
        $analyzeCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($analyzeCode -ne 0) {
        Write-Host "[失败] flutter analyze 存在 issue（退出码 $analyzeCode）" -ForegroundColor Red
    } else {
        Write-Host "[通过] flutter analyze 无 issue" -ForegroundColor Green
    }
}

# ---------------------------------------------------------------------------
# 3. 前端测试
# ---------------------------------------------------------------------------
if ($runFlutterTest) {
    Write-Host "`n[3/3] 前端测试 (flutter test)..." -ForegroundColor Cyan
    Push-Location $frontendDir
    try {
        & $flutterCmd test | Out-Host
        $flutterTestCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($flutterTestCode -ne 0) {
        Write-Host "[失败] 前端测试未通过（退出码 $flutterTestCode）" -ForegroundColor Red
    } else {
        Write-Host "[通过] 前端测试全部通过" -ForegroundColor Green
    }
}

# ---------------------------------------------------------------------------
# 汇总：全部成功才返回 0
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
