# ==============================================================================
# CampusTrade 校园二手交易平台 - 一键启动 PowerShell 脚本
# ------------------------------------------------------------------------------
# 本文件是启动器的唯一实现（start.bat 只是纯 ASCII 转发器，会调用本文件）。
#
# 约定（请勿违反）：
#   1. 所有中文提示只写在这里（.ps1 为 UTF-8 带 BOM，PowerShell 解析可靠）；
#   2. backend\run-backend.cmd、backend\check-port.cmd、frontend\run-frontend.cmd
#      为纯 ASCII 脚本，供 cmd 子窗口调用；如需修改，务必继续保持纯 ASCII；
#   3. 不要再把启动逻辑写回 .bat —— cmd 按控制台代码页（本机 GBK）解析批处理
#      文件，与 UTF-8 字节不匹配时会吞掉引号、错位行，历史上正是它导致后端
#      "拉不起来"且报错被静默吞掉；
#   4. 外部命令（docker compose / mvn / flutter）一律检查退出码：失败就给出中文提示
#      并以非 0 退出，不做"打一行红字然后继续当成功"的处理；
#   5. 工具链路径解析统一走 scripts\toolchain.ps1（环境变量 → PATH → 报错指引），
#      本文件与其它脚本都不得再写死任何盘符；
#   6. 本脚本只报告服务地址，绝不回显 .env 里的口令（MinIO / 数据库口令都只写"见 .env"）。
# ==============================================================================
param(
    # 直接指定启动模式编号（1/2/3/4/5/6/0），用于脚本化调用，跳过交互提问。
    # 例：.\start.ps1 -Mode 6   （跑质量门禁并以其退出码退出）
    [string]$Mode = ""
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$rootDir = $PSScriptRoot
$backendDir = Join-Path $rootDir "backend"
$frontendDir = Join-Path $rootDir "frontend"
$qualityGate = Join-Path $rootDir "scripts\quality-gate.ps1"

# 共享的工具链解析（含可选的 .env.tools 加载）
. (Join-Path $rootDir "scripts\toolchain.ps1")
$toolchainFile = Import-LocalToolchainEnv -RootDir $rootDir

Write-Host "==============================================================================" -ForegroundColor Green
Write-Host "          CampusTrade 校园二手交易平台 - 一键启动控制台" -ForegroundColor Green
Write-Host "==============================================================================" -ForegroundColor Green
Write-Host ""

# 智能匹配本机开发工具链：环境变量 → PATH → 明确报错指引（不再假设任何盘符）
$javaHome = Resolve-JdkHome
$mvnCmd = Resolve-MavenCmd
$flutterCmd = Resolve-FlutterCmd

Write-Host "[检测环境配置]" -ForegroundColor Cyan
Write-Host "* 项目目录 : $rootDir"
if ($toolchainFile) { Write-Host "* 本地工具链: $toolchainFile" }
Write-Host "* JAVA_HOME: $(if ($javaHome) { $javaHome } else { '(未找到)' })"
Write-Host "* Maven    : $(if ($mvnCmd) { $mvnCmd } else { '(未找到)' })"
Write-Host "* Flutter  : $(if ($flutterCmd) { $flutterCmd } else { '(未找到)' })"
Write-Host ""

Write-Host "请选择启动模式 (输入对应数字，直接回车默认 [1]):" -ForegroundColor Yellow
Write-Host "[1] 完整启动 (Docker + 后端 + 前端 Chrome Web) [推荐]"
Write-Host "[2] 桌面端模式 (Docker + 后端 + 前端 Windows 原生桌面版)"
Write-Host "[3] 仅启动基础设施 (PostgreSQL + Redis + MinIO)"
Write-Host "[4] 仅启动后端服务 (Spring Boot)"
Write-Host "[5] 仅启动前端应用 (Flutter Chrome)"
Write-Host "[6] 运行全量质量门禁 (后端 mvn test + 前端 flutter analyze + flutter test)"
Write-Host "[0] 退出"
Write-Host ""

if ([string]::IsNullOrWhiteSpace($Mode)) {
    $choice = Read-Host "请输入选项编号 [默认 1]"
    if ([string]::IsNullOrWhiteSpace($choice)) { $choice = "1" }
} else {
    $choice = $Mode.Trim()
    Write-Host "[*] 已通过 -Mode 参数指定模式: $choice" -ForegroundColor Cyan
}

# 后端是否就绪：失败时不再启动前端，也不再打印"启动完成"导航
$script:backendOk = $true
# 任一步骤失败即置为 $true，脚本最终以非 0 退出
$script:failed = $false

function Start-DockerServices {
    param([string]$DockerCmd)

    Write-Host "`n[*] 正在启动 Docker 容器 (PostgreSQL 16, Redis 7, MinIO)..." -ForegroundColor Cyan
    Push-Location $rootDir
    try {
        & $DockerCmd compose up -d
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($code -ne 0) {
        Write-Host "[错误] docker compose up -d 失败（退出码 $code）。" -ForegroundColor Red
        Write-Host "       常见原因：Docker Desktop 未启动、变量缺失（项目根目录 .env 未按 .env.example 填好）、" -ForegroundColor Red
        Write-Host "       或镜像无法拉取。请先执行 docker compose config 查看报错，再重试。" -ForegroundColor Red
        $script:failed = $true
        return
    }
    Write-Host "[成功] 基础设施容器已就绪！" -ForegroundColor Green
}

function Test-BackendPort {
    return [bool](Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)
}

function Get-BackendPortOwner {
    # 返回占用 8080 的进程对象；端口空闲时返回 $null
    $conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $conn) { return $null }
    return Get-CimInstance Win32_Process -Filter "ProcessId=$($conn.OwningProcess)" -ErrorAction SilentlyContinue
}

function Start-BackendService {
    Write-Host "`n[*] 检查后端服务状态..." -ForegroundColor Cyan

    # 先解析工具链：缺 JDK/Maven 时直接给出中文指引，不再拉起一个注定失败的 cmd 窗口
    if (-not $javaHome) {
        Write-ToolchainGuidance -ToolName "JDK 21" -EnvVar "JAVA_HOME" -InstallHint "安装 OpenJDK 21 LTS（或让本机已有的 JDK 21 出现在 PATH 中）"
        $script:backendOk = $false
        $script:failed = $true
        return
    }
    if (-not $mvnCmd) {
        Write-ToolchainGuidance -ToolName "Maven" -EnvVar "MAVEN_HOME" -InstallHint "安装 Apache Maven 3.9+ 并确保 mvn 在 PATH 中"
        $script:backendOk = $false
        $script:failed = $true
        return
    }

    $owner = Get-BackendPortOwner
    if ($owner) {
        if ($owner.CommandLine -like "*com.campustrade.CampusTradeApplication*") {
            Write-Host "[提示] 端口 8080 已由 CampusTrade 后端占用，无需重复启动。" -ForegroundColor Yellow
        } else {
            $appClass = if ($owner.CommandLine -match '([\w.]+Application)') { $Matches[1] } else { $owner.Name }
            Write-Host "[警告] 端口 8080 已被其它程序占用，CampusTrade 后端无法启动。" -ForegroundColor Red
            Write-Host "       占用进程: $appClass (PID $($owner.ProcessId))" -ForegroundColor Red
            Write-Host "       请先停止该程序后再启动本项目后端。" -ForegroundColor Red
            $script:backendOk = $false
            $script:failed = $true
        }
        return
    }
    Write-Host "[*] 正在新窗口中启动 Spring Boot 3..." -ForegroundColor Cyan
    # 启动细节交由 backend\run-backend.cmd 处理：JDK/Maven 解析与失败提示都在该脚本内完成。
    # 不要在本文件里拼接 "set JAVA_HOME=... &"：cmd 会把 & 前的空格并入变量值，
    # Maven 会因 JAVA_HOME 无效而直接退出。
    Start-Process -FilePath "cmd.exe" -WorkingDirectory $backendDir -ArgumentList "/k", "title CampusTrade Backend && run-backend.cmd"
    Write-Host "[*] 等待本项目后端监听 8080 (最长 120 秒)..." -NoNewline
    # 就绪判据必须是"8080 的持有者 = 本项目后端"，而不是"8080 有人监听"。
    # 否则会出现这类假成功：检查时端口空闲 → 拉起后端 → 期间别的程序抢占了 8080
    # → 轮询看到"有人监听"就报成功，而我们的后端其实早就因端口被占退出了（实测发生过）。
    $attempts = 0
    $ready = $false
    $hijackedBy = $null
    while ($attempts -lt 60) {
        $currentOwner = Get-BackendPortOwner
        if ($currentOwner) {
            if ($currentOwner.CommandLine -like "*com.campustrade.CampusTradeApplication*") {
                $ready = $true
                break
            }
            $hijackedBy = $currentOwner
            break
        }
        Start-Sleep -Seconds 2
        Write-Host "." -NoNewline
        $attempts++
    }

    if ($ready) {
        Write-Host "`n[成功] 后端服务已就绪！(http://127.0.0.1:8080/api)" -ForegroundColor Green
    } elseif ($hijackedBy) {
        # 端口在启动过程中被别人抢走：明确报错，绝不报成功
        $appClass = if ($hijackedBy.CommandLine -match '([\w.]+Application)') { $Matches[1] } else { $hijackedBy.Name }
        Write-Host "`n[错误] 端口 8080 在本项目后端启动过程中被其它程序占用，后端无法监听。" -ForegroundColor Red
        Write-Host "       占用进程: $appClass (PID $($hijackedBy.ProcessId))" -ForegroundColor Red
        Write-Host "       请停止该程序后重试，或为两个项目分配不同端口。" -ForegroundColor Red
        $script:backendOk = $false
        $script:failed = $true
    } else {
        Write-Host "`n[错误] 后端启动超时。请查看 `"CampusTrade Backend`" 窗口中的报错信息。" -ForegroundColor Red
        $script:backendOk = $false
        $script:failed = $true
    }
}

function Start-FrontendApp {
    param([string]$Device)

    Write-Host "`n[*] 正在新窗口中启动 Flutter（$Device）..." -ForegroundColor Cyan
    if (-not $flutterCmd) {
        Write-ToolchainGuidance -ToolName "Flutter SDK" -EnvVar "FLUTTER_ROOT" -InstallHint "安装 Flutter 3.x 并确保 flutter 在 PATH 中"
        $script:failed = $true
        return
    }
    # 同样交给 frontend\run-frontend.cmd：Flutter 路径解析与失败提示都在该脚本内完成。
    Start-Process -FilePath "cmd.exe" -WorkingDirectory $frontendDir -ArgumentList "/k", "title CampusTrade Frontend && run-frontend.cmd run -d $Device"
    if ($Device -eq "chrome") {
        Write-Host "[成功] 前端启动中，Chrome 稍后将自动打开页面！" -ForegroundColor Green
    } else {
        Write-Host "[成功] 桌面应用编译中，窗口将自动弹出！" -ForegroundColor Green
    }
}

function Invoke-QualityGate {
    # 选项 6：把结果与退出码完全交给 scripts\quality-gate.ps1（唯一的测试入口定义），
    # 本函数只负责透传退出码，绝不在此之后继续打印"启动完成"横幅。
    #
    # ⚠️ 注意这里的 Out-Host：本脚本调用的是"子脚本"，而 PowerShell 会把被调用脚本里
    # 未被消费的管道输出（mvn / flutter 的原生 stdout 会进入管道）收集成"返回值"。
    # 若直接写 `& $qualityGate`，返回值会变成几百行 Maven 日志，
    # 后续的 `exit $gateCode` 也就会静默变成 exit 0 —— 测试失败却报成功。
    # 用 Out-Host 把输出显式打到控制台，函数返回值才是干净的数字退出码。
    Write-Host "`n[*] 正在执行全量质量门禁..." -ForegroundColor Cyan
    if (-not (Test-Path $qualityGate)) {
        Write-Host "[错误] 未找到质量门禁脚本: $qualityGate" -ForegroundColor Red
        return 1
    }
    & $qualityGate | Out-Host
    return [int]$LASTEXITCODE
}

# 需要 Docker 的选项先确认 docker CLI 与 daemon 可用
function Assert-DockerReady {
    $dockerCmd = Resolve-DockerCmd
    if (-not $dockerCmd) {
        Write-Host "[错误] 未找到 docker 命令：请安装 Docker Desktop 并确保 docker 在 PATH 中。" -ForegroundColor Red
        $script:failed = $true
        return $null
    }
    if (-not (Test-DockerAvailable -DockerCmd $dockerCmd)) {
        Write-Host "[错误] docker 命令存在但 Docker 引擎不可用（Docker Desktop 未启动？）。" -ForegroundColor Red
        Write-Host "       请先启动 Docker Desktop，看到鲸鱼图标变为运行中后重试。" -ForegroundColor Red
        $script:failed = $true
        return $null
    }
    return $dockerCmd
}

switch ($choice) {
    "1" {
        $dockerCmd = Assert-DockerReady
        if ($dockerCmd) { Start-DockerServices -DockerCmd $dockerCmd }
        Start-BackendService
        if ($script:backendOk) { Start-FrontendApp -Device "chrome" }
    }
    "2" {
        $dockerCmd = Assert-DockerReady
        if ($dockerCmd) { Start-DockerServices -DockerCmd $dockerCmd }
        Start-BackendService
        if ($script:backendOk) { Start-FrontendApp -Device "windows" }
    }
    "3" {
        $dockerCmd = Assert-DockerReady
        if ($dockerCmd) { Start-DockerServices -DockerCmd $dockerCmd }
    }
    "4" {
        Start-BackendService
    }
    "5" {
        Start-FrontendApp -Device "chrome"
    }
    "6" {
        $gateCode = Invoke-QualityGate
        Write-Host ""
        if ($gateCode -ne 0) {
            Write-Host "==============================================================================" -ForegroundColor Red
            Write-Host "   质量门禁未通过（退出码 $gateCode）：后端测试 / 前端分析 / 前端测试 至少一项失败。" -ForegroundColor Red
            Write-Host "==============================================================================" -ForegroundColor Red
            exit $gateCode
        }
        Write-Host "==============================================================================" -ForegroundColor Green
        Write-Host "   质量门禁通过：后端测试 + 前端静态分析 + 前端测试 全部成功。" -ForegroundColor Green
        Write-Host "==============================================================================" -ForegroundColor Green
        # 跑测试是"验证"而不是"启动"：这里直接结束，不打印启动完成导航
        exit 0
    }
    "0" {
        Write-Host "已退出。"
        exit 0
    }
    Default {
        Write-Host "无效选项，默认执行完整启动..."
        $dockerCmd = Assert-DockerReady
        if ($dockerCmd) { Start-DockerServices -DockerCmd $dockerCmd }
        Start-BackendService
        if ($script:backendOk) { Start-FrontendApp -Device "chrome" }
    }
}

if ($script:failed -or -not $script:backendOk) {
    Write-Host "`n==============================================================================" -ForegroundColor Red
    Write-Host "   启动失败：上一步骤未成功，已中止后续步骤。" -ForegroundColor Red
    Write-Host "   请按上方提示处理后重新运行本启动器。" -ForegroundColor Red
    Write-Host "==============================================================================" -ForegroundColor Red
    exit 1
}

# 只回显端口（不涉及口令）；端口取自 .env，改过端口时导航也跟着变
function Get-EnvPort {
    param([string]$Key, [string]$Default)
    $envFile = Join-Path $rootDir ".env"
    if (-not (Test-Path $envFile)) { return $Default }
    foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
        $trimmed = $line.Trim()
        if ($trimmed -eq "" -or $trimmed.StartsWith("#")) { continue }
        $index = $trimmed.IndexOf("=")
        if ($index -lt 1) { continue }
        if ($trimmed.Substring(0, $index).Trim() -eq $Key) { return $trimmed.Substring($index + 1).Trim() }
    }
    return $Default
}

$pgPort = Get-EnvPort -Key "POSTGRES_PORT" -Default "15435"
$redisPort = Get-EnvPort -Key "REDIS_PORT" -Default "6379"
$minioConsolePort = Get-EnvPort -Key "MINIO_CONSOLE_PORT" -Default "9001"

Write-Host "`n==============================================================================" -ForegroundColor Green
Write-Host "                      CampusTrade 启动完成导航" -ForegroundColor Green
Write-Host "==============================================================================" -ForegroundColor Green
Write-Host "  * 前端应用 Web 端   : 稍后将在 Chrome 浏览器中自动展示" -ForegroundColor White
Write-Host "  * 后端 API 接口     : http://127.0.0.1:8080/api" -ForegroundColor White
Write-Host "  * 接口文档          : 暂无在线文档页（未引入 springdoc/swagger-ui）" -ForegroundColor White
Write-Host "  * MinIO 管理控制台  : http://127.0.0.1:$minioConsolePort (账号与口令见项目根目录 .env，本脚本不回显)" -ForegroundColor White
Write-Host "  * PostgreSQL 16     : 127.0.0.1:$pgPort (库名 campustrade，口令见 .env)" -ForegroundColor White
Write-Host "  * Redis 7           : 127.0.0.1:$redisPort (已启用口令，口令见 .env)" -ForegroundColor White
Write-Host "==============================================================================" -ForegroundColor Green
Write-Host "如需停止服务，可直接运行根目录下的 stop.bat 或 stop.ps1。`n" -ForegroundColor Cyan
exit 0
