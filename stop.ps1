# ==============================================================================
# CampusTrade 校园二手交易平台 - 一键停止 PowerShell 脚本
#
# 约定：外部命令（docker compose stop）一律检查退出码，失败给出中文提示并以非 0 退出。
#       本文件为 UTF-8 with BOM，请勿改成其它编码。
# ==============================================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$rootDir = $PSScriptRoot
. (Join-Path $rootDir "scripts\toolchain.ps1")
Import-LocalToolchainEnv -RootDir $rootDir | Out-Null

$script:failed = $false

Write-Host "==============================================================================" -ForegroundColor Red
Write-Host "          CampusTrade 校园二手交易平台 - 一键停止控制台" -ForegroundColor Red
Write-Host "==============================================================================" -ForegroundColor Red
Write-Host ""

# 1. 查找并停止端口 8080 上的进程（区分是否为本项目后端，避免误杀其它工程的服务）
Write-Host "[1/2] 正在检查并停止后端 Spring Boot 进程 (端口 8080)..." -ForegroundColor Cyan
$conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
$owner = if ($conn) { Get-CimInstance Win32_Process -Filter "ProcessId=$($conn.OwningProcess)" -ErrorAction SilentlyContinue } else { $null }

if (-not $owner) {
    Write-Host "[提示] 端口 8080 无占用，后端未运行。" -ForegroundColor Yellow
} elseif ($owner.CommandLine -like "*com.campustrade.CampusTradeApplication*") {
    Write-Host "终止 CampusTrade 后端进程 (PID $($owner.ProcessId))..." -ForegroundColor Yellow
    Stop-Process -Id $owner.ProcessId -Force -ErrorAction SilentlyContinue
    Write-Host "[成功] 后端服务进程已终止。" -ForegroundColor Green
} else {
    $appClass = if ($owner.CommandLine -match '([\w.]+Application)') { $Matches[1] } else { $owner.Name }
    Write-Host "[警告] 端口 8080 被其它程序占用: $appClass (PID $($owner.ProcessId))" -ForegroundColor Red
    Write-Host "       它不是 CampusTrade 的后端，默认不结束它。" -ForegroundColor Red
    $answer = Read-Host "       仍要结束该进程并释放 8080 吗? (y/N)"
    if ($answer -eq 'y' -or $answer -eq 'Y') {
        Stop-Process -Id $owner.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Host "[成功] 已结束该进程，端口 8080 已释放。" -ForegroundColor Green
    } else {
        Write-Host "[跳过] 未结束其它程序的进程。" -ForegroundColor Yellow
    }
}

# 2. 停止 Docker 容器
Write-Host "`n[2/2] 正在停止 Docker 基础设施容器 (PostgreSQL, Redis, MinIO)..." -ForegroundColor Cyan
$dockerCmd = Resolve-DockerCmd
if (-not $dockerCmd) {
    Write-Host "[错误] 未找到 docker 命令：请安装 Docker Desktop 并确保 docker 在 PATH 中。" -ForegroundColor Red
    Write-Host "       容器未做任何处理（如果它们之前由 Docker Desktop 启动，请在 Docker 面板里关闭）。" -ForegroundColor Red
    exit 1
}
if (-not (Test-DockerAvailable -DockerCmd $dockerCmd)) {
    Write-Host "[错误] docker 命令存在但 Docker 引擎不可用（Docker Desktop 未启动？）。" -ForegroundColor Red
    Write-Host "       容器未做任何处理，请先启动 Docker Desktop 后重试。" -ForegroundColor Red
    exit 1
}

Push-Location $rootDir
try {
    & $dockerCmd compose stop
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($code -ne 0) {
    Write-Host "[错误] docker compose stop 失败（退出码 $code），容器可能仍在运行。" -ForegroundColor Red
    Write-Host "       请执行 docker compose ps 查看当前状态，或到 Docker Desktop 面板手动停止。" -ForegroundColor Red
    $script:failed = $true
} else {
    Write-Host "[成功] Docker 容器已安全停止（数据卷保留，数据不会丢失）。" -ForegroundColor Green
}

Write-Host "`n==============================================================================" -ForegroundColor $(if ($script:failed) { "Red" } else { "Red" })
if ($script:failed) {
    Write-Host "停止过程中出现错误，请按上方提示处理。" -ForegroundColor Red
    Write-Host "==============================================================================" -ForegroundColor Red
    Write-Host ""
    exit 1
}
Write-Host "所有 CampusTrade 本地开发服务已安全停止。" -ForegroundColor Red
Write-Host "如需彻底清理容器与网络，可运行: docker compose down" -ForegroundColor Gray
Write-Host "  注意：千万不要加 -v 参数（docker compose down -v 会删除 campustrade_* 数据卷，" -ForegroundColor Yellow
Write-Host "        本地开发库与 MinIO 里的图片会一并消失）。" -ForegroundColor Yellow
Write-Host "==============================================================================" -ForegroundColor Red
Write-Host ""
exit 0
