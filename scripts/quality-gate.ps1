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
#   4. 本文件为 UTF-8 with BOM，请勿转成其它编码（PowerShell 5.1 依赖 BOM 正确读取中文）；
#   5. 后端测试的中间件（PostgreSQL / Redis / MinIO）由 Testcontainers 在测试 JVM 内现拉现用，
#      因此开跑前必须先确认 Docker 可用（复用 toolchain.ps1 的 Test-DockerAvailable）：
#      不可用时在这里就给中文提示并非 0 退出，而不是让 mvn test 抛一堆英文异常到日志里。
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

# ---------------------------------------------------------------------------
# 0.2 中间件依赖检查：后端测试需要可用的 Docker（Testcontainers）
#
# 为什么需要：测试用的 PostgreSQL 16 / Redis 7 / MinIO 不是外部服务，而是
#   com.campustrade.support.TestContainersConfig 在测试 JVM 内用 Testcontainers
#   拉起的"一次性容器"。Docker CLI 或 daemon 不可用时，mvn test 会在启动容器阶段
#   抛出英文异常（典型信息：Could not find a valid Docker environment / docker info failed），
#   报错位置离根因很远。所以在跑测试之前先检查，并明确说清"为什么"和"怎么办"。
#
# 为什么只在 runBackend 时检查：flutter analyze / flutter test 不需要 Docker，
#   `-Only analyze`、`-Only flutter-test` 在没有 Docker 的机器上照样应当可用。
# ---------------------------------------------------------------------------
if ($runBackend) {
    $dockerCmd = Resolve-DockerCmd
    if (-not $dockerCmd -or -not (Test-DockerAvailable -DockerCmd $dockerCmd)) {
        Write-Host "[失败] 后端测试需要 Docker，但当前 Docker 不可用。" -ForegroundColor Red
        if (-not $dockerCmd) {
            Write-Host "       当前解析结果：未找到 docker 命令（未安装，或不在 PATH 中）。" -ForegroundColor Red
        } else {
            Write-Host "       当前解析结果：docker 命令存在（$dockerCmd），但 docker info 未通过。" -ForegroundColor Red
            Write-Host "       即 Docker CLI 装好了，Docker 引擎（daemon）没在跑，或当前用户无权访问它。" -ForegroundColor Red
        }
        Write-Host "       为什么需要：后端测试的 PostgreSQL / Redis / MinIO 由 Testcontainers 在测试 JVM 内" -ForegroundColor Red
        Write-Host "       现拉现用（backend/src/test/java/com/campustrade/support/TestContainersConfig.java），" -ForegroundColor Red
        Write-Host "       没有可用的 Docker 时 mvn test 会在启动容器阶段失败。" -ForegroundColor Red
        Write-Host "       怎么办（任选其一）：" -ForegroundColor Yellow
        Write-Host "         1) 启动 Docker，再重跑本门禁：" -ForegroundColor Yellow
        Write-Host "            Windows：开始菜单启动 Docker Desktop，等托盘鲸鱼图标变为 Running（首次启动约 1 分钟）" -ForegroundColor Gray
        Write-Host "            Linux  ：sudo systemctl start docker" -ForegroundColor Gray
        Write-Host "            自检    ：docker info            （退出码 0 = 可用）" -ForegroundColor Gray
        Write-Host "         2) 只想跑不需要 Docker 的两项（后端测试会被跳过）：" -ForegroundColor Yellow
        Write-Host "            powershell -NoProfile -ExecutionPolicy Bypass -File scripts/quality-gate.ps1 -Only analyze" -ForegroundColor Gray
        Write-Host "            powershell -NoProfile -ExecutionPolicy Bypass -File scripts/quality-gate.ps1 -Only flutter-test" -ForegroundColor Gray
        exit 1
    }
    Write-Host "[通过] Docker 可用：$dockerCmd（后端测试的中间件将由 Testcontainers 现拉现用）" -ForegroundColor Green
    Write-Host ""
}

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
