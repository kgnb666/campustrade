# ==============================================================================
# CampusTrade 校园二手交易平台 - 一键停止 PowerShell 脚本
# ==============================================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$rootDir = $PSScriptRoot

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
Set-Location $rootDir
docker compose stop
Write-Host "[成功] Docker 容器已安全停止。" -ForegroundColor Green

Write-Host "`n==============================================================================" -ForegroundColor Red
Write-Host "所有 CampusTrade 本地开发服务已安全停止。" -ForegroundColor Red
Write-Host "若需彻底清理容器与网络，可运行: docker compose down" -ForegroundColor Gray
Write-Host "==============================================================================" -ForegroundColor Red
Write-Host ""
