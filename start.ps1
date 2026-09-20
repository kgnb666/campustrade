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
#      "拉不起来"且报错被静默吞掉。
# ==============================================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$rootDir = $PSScriptRoot
$backendDir = Join-Path $rootDir "backend"
$frontendDir = Join-Path $rootDir "frontend"

Write-Host "==============================================================================" -ForegroundColor Green
Write-Host "          CampusTrade 校园二手交易平台 - 一键启动控制台" -ForegroundColor Green
Write-Host "==============================================================================" -ForegroundColor Green
Write-Host ""

# 智能匹配本机开发工具链
$javaHome = if (Test-Path "D:\yp3\.tools\jdk21") { "D:\yp3\.tools\jdk21" } else { $env:JAVA_HOME }
$mvnCmd = if (Test-Path "D:\yp3\.tools\maven\bin\mvn.cmd") { "D:\yp3\.tools\maven\bin\mvn.cmd" } else { "mvn" }
$flutterCmd = if (Test-Path "D:\flutter_sdk\flutter\bin\flutter.bat") { "D:\flutter_sdk\flutter\bin\flutter.bat" } elseif (Test-Path "C:\flutter\bin\flutter.bat") { "C:\flutter\bin\flutter.bat" } else { "flutter" }

Write-Host "[检测环境配置]" -ForegroundColor Cyan
Write-Host "* 项目目录 : $rootDir"
Write-Host "* JAVA_HOME: $javaHome"
Write-Host "* Maven    : $mvnCmd"
Write-Host "* Flutter  : $flutterCmd"
Write-Host ""

Write-Host "请选择启动模式 (输入对应数字，直接回车默认 [1]):" -ForegroundColor Yellow
Write-Host "[1] 完整启动 (Docker + 后端 + 前端 Chrome Web) [推荐]"
Write-Host "[2] 桌面端模式 (Docker + 后端 + 前端 Windows 原生桌面版)"
Write-Host "[3] 仅启动基础设施 (PostgreSQL + Redis + MinIO)"
Write-Host "[4] 仅启动后端服务 (Spring Boot)"
Write-Host "[5] 仅启动前端应用 (Flutter Chrome)"
Write-Host "[6] 运行全量自动化测试 (后端 220 项 + 前端 95 项)"
Write-Host "[0] 退出"
Write-Host ""

$choice = Read-Host "请输入选项编号 [默认 1]"
if ([string]::IsNullOrWhiteSpace($choice)) { $choice = "1" }

# 后端是否就绪：失败时不再启动前端，也不再打印"启动完成"导航
$backendOk = $true

function Start-DockerServices {
    Write-Host "`n[*] 正在启动 Docker 容器 (PostgreSQL 16, Redis 7, MinIO)..." -ForegroundColor Cyan
    Set-Location $rootDir
    docker compose up -d
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
        }
        return
    }
    Write-Host "[*] 正在新窗口中启动 Spring Boot 3..." -ForegroundColor Cyan
    # 启动细节交由 backend\run-backend.cmd 处理：JDK/Maven 解析与失败提示都在该脚本内完成。
    # 不要在本文件里拼接 "set JAVA_HOME=... &"：cmd 会把 & 前的空格并入变量值，
    # Maven 会因 JAVA_HOME 无效而直接退出。
    Start-Process -FilePath "cmd.exe" -WorkingDirectory $backendDir -ArgumentList "/k", "title CampusTrade Backend && run-backend.cmd"
    Write-Host "[*] 等待后端端口 8080 监听 (最长 120 秒)..." -NoNewline
    $attempts = 0
    while (-not (Test-BackendPort) -and ($attempts -lt 60)) {
        Start-Sleep -Seconds 2
        Write-Host "." -NoNewline
        $attempts++
    }
    if (Test-BackendPort) {
        Write-Host "`n[成功] 后端服务已就绪！(http://127.0.0.1:8080/api)" -ForegroundColor Green
    } else {
        Write-Host "`n[错误] 后端启动超时。请查看 `"CampusTrade Backend`" 窗口中的报错信息。" -ForegroundColor Red
        $script:backendOk = $false
    }
}

function Start-FrontendWeb {
    Write-Host "`n[*] 正在新窗口中启动 Flutter Chrome Web..." -ForegroundColor Cyan
    # 同样交给 frontend\run-frontend.cmd：Flutter 路径解析与失败提示都在该脚本内完成。
    Start-Process -FilePath "cmd.exe" -WorkingDirectory $frontendDir -ArgumentList "/k", "title CampusTrade Frontend && run-frontend.cmd run -d chrome"
    Write-Host "[成功] 前端启动中，Chrome 稍后将自动打开页面！" -ForegroundColor Green
}

function Start-FrontendDesktop {
    Write-Host "`n[*] 正在启动 Flutter Windows 桌面端..." -ForegroundColor Cyan
    Start-Process -FilePath "cmd.exe" -WorkingDirectory $frontendDir -ArgumentList "/k", "title CampusTrade Frontend && run-frontend.cmd run -d windows"
    Write-Host "[成功] 桌面应用编译中，窗口将自动弹出！" -ForegroundColor Green
}

switch ($choice) {
    "1" {
        Start-DockerServices
        Start-BackendService
        if ($backendOk) { Start-FrontendWeb }
    }
    "2" {
        Start-DockerServices
        Start-BackendService
        if ($backendOk) { Start-FrontendDesktop }
    }
    "3" {
        Start-DockerServices
    }
    "4" {
        Start-BackendService
    }
    "5" {
        Start-FrontendWeb
    }
    "6" {
        Write-Host "`n[*] 正在执行全量测试套件..." -ForegroundColor Cyan
        Set-Location $backendDir
        $env:JAVA_HOME = $javaHome
        & $mvnCmd test
        Set-Location $frontendDir
        & $flutterCmd test
        Set-Location $rootDir
    }
    "0" {
        Write-Host "已退出。"
        exit
    }
    Default {
        Write-Host "无效选项，默认执行完整启动..."
        Start-DockerServices
        Start-BackendService
        if ($backendOk) { Start-FrontendWeb }
    }
}

if (-not $backendOk) {
    Write-Host "`n==============================================================================" -ForegroundColor Red
    Write-Host "   启动失败：CampusTrade 后端未就绪，已跳过前端启动。" -ForegroundColor Red
    Write-Host "   请按上方提示处理后重新运行本启动器。" -ForegroundColor Red
    Write-Host "==============================================================================" -ForegroundColor Red
    exit 1
}

Write-Host "`n==============================================================================" -ForegroundColor Green
Write-Host "                      CampusTrade 启动完成导航" -ForegroundColor Green
Write-Host "==============================================================================" -ForegroundColor Green
Write-Host "  * 前端应用 Web 端   : 稍后将在 Chrome 浏览器中自动展示" -ForegroundColor White
Write-Host "  * 后端 API 接口     : http://127.0.0.1:8080/api" -ForegroundColor White
Write-Host "  * Swagger 文档      : http://127.0.0.1:8080/api/swagger-ui/index.html" -ForegroundColor White
Write-Host "  * MinIO 管理控制台  : http://127.0.0.1:9001 (账号: campustrade / 密码: campustrade123)" -ForegroundColor White
Write-Host "  * PostgreSQL 16     : 127.0.0.1:15435 (库名: campustrade)" -ForegroundColor White
Write-Host "  * Redis 7           : 127.0.0.1:6379" -ForegroundColor White
Write-Host "==============================================================================" -ForegroundColor Green
Write-Host "如需停止服务，可直接运行根目录下的 stop.bat 或 stop.ps1。`n" -ForegroundColor Cyan
