# ==============================================================================
#  CampusTrade 工具链解析（由 start.ps1 / quality-gate.ps1 点源加载）
#
#  解析顺序固定为三步，任何一步都不再依赖"写死的盘符"：
#    1. 环境变量        JAVA_HOME / MAVEN_HOME(或 M2_HOME) / FLUTTER_ROOT
#    2. 系统 PATH        java / mvn(.cmd) / flutter(.bat)
#    3. 明确报错指引     打印"缺什么、怎么装、怎么配"，绝不用一个猜测的路径继续跑
#
#  第 0 步（可选）：本机私有配置文件 .env.tools（项目根目录，已 git-ignore，纯 ASCII）
#  会在上面三步之前被加载进当前进程的环境变量，用于持久化"这台机器上 JDK/Maven/Flutter 装在哪"。
#  它只是环境变量的持久化载体，不参与解析顺序的语义：真实环境变量优先级更高。
#    例（.env.tools）：
#      JAVA_HOME=D:\tools\jdk21
#      MAVEN_HOME=D:\tools\maven
#      FLUTTER_ROOT=D:\tools\flutter
#
#  本文件为 UTF-8 with BOM，请勿改成其它编码（PowerShell 5.1 依赖 BOM 正确读取中文）。
# ==============================================================================

function Import-LocalToolchainEnv {
    <#
      把 .env.tools 里形如 KEY=VALUE 的行导入当前进程环境变量。
      已存在的环境变量不会被覆盖（真实环境变量优先）。
      解析规则与 backend/run-backend.cmd 解析 .env 保持一致：跳过空行与 # 开头的注释，
      以第一个 '=' 切分，值保持原样。
    #>
    param([Parameter(Mandatory = $true)][string]$RootDir)

    $file = Join-Path $RootDir ".env.tools"
    if (-not (Test-Path $file)) { return $null }

    foreach ($line in Get-Content -LiteralPath $file -Encoding UTF8) {
        $trimmed = $line.Trim()
        if ($trimmed -eq "" -or $trimmed.StartsWith("#")) { continue }
        $index = $trimmed.IndexOf("=")
        if ($index -lt 1) { continue }
        $key = $trimmed.Substring(0, $index).Trim()
        $value = $trimmed.Substring($index + 1).Trim()
        if ($key -eq "" -or $value -eq "") { continue }
        if (-not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($key, "Process"))) { continue }
        Set-Item -Path ("Env:" + $key) -Value $value
    }
    return $file
}

function Get-JavaMajorVersion {
    param([Parameter(Mandatory = $true)][string]$JavaExe)

    # java -version 把版本号写到 stderr；首行形如：openjdk version "21.0.4" 2024-07-16
    $text = (& $JavaExe -version 2>&1 | Out-String)
    $match = [regex]::Match($text, 'version "(\d+)')
    if (-not $match.Success) { return $null }
    return [int]$match.Groups[1].Value
}

function Resolve-JdkHome {
    <#
      返回可用的 JDK 主目录；找不到时返回 $null（由调用方给出中文指引）。
      额外做一次主版本校验：项目要求 Java 21，用低版本 JDK 构建只会得到
      一堆难懂的编译错误，不如在这里直接说清楚。
    #>
    $candidate = $null

    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME) -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        $candidate = (Resolve-Path $env:JAVA_HOME).Path
    }

    if (-not $candidate) {
        $javaCmd = Get-Command java.exe -ErrorAction SilentlyContinue
        if (-not $javaCmd) { $javaCmd = Get-Command java -ErrorAction SilentlyContinue }
        if ($javaCmd) {
            # java.exe 位于 <JDK>\bin 下，其上两级即 JDK 主目录
            $binDir = Split-Path -Parent $javaCmd.Source
            if ((Split-Path -Leaf $binDir) -ieq "bin") { $candidate = Split-Path -Parent $binDir }
        }
    }

    if (-not $candidate) { return $null }

    $major = Get-JavaMajorVersion -JavaExe (Join-Path $candidate "bin\java.exe")
    if ($null -ne $major -and $major -lt 21) {
        Write-Host "[错误] 找到的 JDK 主版本为 $major，本项目要求 Java 21。" -ForegroundColor Red
        Write-Host "       当前解析到: $candidate" -ForegroundColor Red
        return $null
    }

    return $candidate
}

function Resolve-MavenCmd {
    <# 返回可执行的 Maven 命令路径；找不到时返回 $null。 #>
    foreach ($homeVar in @($env:MAVEN_HOME, $env:M2_HOME)) {
        if ([string]::IsNullOrWhiteSpace($homeVar)) { continue }
        $cmd = Join-Path $homeVar "bin\mvn.cmd"
        if (Test-Path $cmd) { return (Resolve-Path $cmd).Path }
    }

    foreach ($name in @("mvn.cmd", "mvn")) {
        $found = Get-Command $name -ErrorAction SilentlyContinue
        if ($found) { return $found.Source }
    }
    return $null
}

function Resolve-FlutterCmd {
    <# 返回可执行的 Flutter 命令路径；找不到时返回 $null。 #>
    if (-not [string]::IsNullOrWhiteSpace($env:FLUTTER_ROOT)) {
        foreach ($leaf in @("bin\flutter.bat", "bin\flutter")) {
            $cmd = Join-Path $env:FLUTTER_ROOT $leaf
            if (Test-Path $cmd) { return (Resolve-Path $cmd).Path }
        }
    }

    foreach ($name in @("flutter.bat", "flutter")) {
        $found = Get-Command $name -ErrorAction SilentlyContinue
        if ($found) { return $found.Source }
    }
    return $null
}

function Resolve-DockerCmd {
    <# 返回 docker 可执行文件路径；找不到时返回 $null（提示"未安装或未启动 Docker Desktop"）。 #>
    $found = Get-Command docker.exe -ErrorAction SilentlyContinue
    if (-not $found) { $found = Get-Command docker -ErrorAction SilentlyContinue }
    if ($found) { return $found.Source }
    return $null
}

function Write-ToolchainGuidance {
    <#
      工具链缺失时的统一中文指引：说清"缺什么 / 三种解决办法"，并把 .env.tools 的写法贴出来。
    #>
    param(
        [Parameter(Mandatory = $true)][string]$ToolName,
        [Parameter(Mandatory = $true)][string]$EnvVar,
        [Parameter(Mandatory = $true)][string]$InstallHint
    )

    Write-Host "[错误] 未找到 $ToolName。" -ForegroundColor Red
    Write-Host "       本脚本按「环境变量 $EnvVar → 系统 PATH → 报错指引」的顺序查找，两处都没有。" -ForegroundColor Red
    Write-Host "       三种解决方式（任选其一）：" -ForegroundColor Yellow
    Write-Host "         1) 安装：$InstallHint" -ForegroundColor Yellow
    Write-Host "         2) 设置系统环境变量 $EnvVar 指向安装目录，然后重开终端；" -ForegroundColor Yellow
    Write-Host "         3) 或在本项目根目录创建 .env.tools（已 git-ignore，纯 ASCII），写入：" -ForegroundColor Yellow
    Write-Host "              $EnvVar=<安装目录>" -ForegroundColor Gray
}

function Test-DockerAvailable {
    <# docker CLI 与 daemon 都要可用，否则后面的 docker compose 只会抛一堆英文错误。 #>
    param([Parameter(Mandatory = $true)][string]$DockerCmd)

    & $DockerCmd info *> $null
    return ($LASTEXITCODE -eq 0)
}
