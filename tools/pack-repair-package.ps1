#Requires -Version 7.0
<#
.SYNOPSIS
    把修复包目录或 ZIP 打包成加密的 .clpkg 文件。

.DESCRIPTION
    内部调用 tools/PackageTool.java（JDK 11+ 单文件源码运行）。
    密码优先使用 -Password，未提供时交互输入（输入两次确认）。
    密码通过环境变量传给 Java 进程，不写入命令行。

.EXAMPLE
    pwsh tools/pack-repair-package.ps1 -Source .\my-package -Output .\my-package.clpkg

.EXAMPLE
    pwsh tools/pack-repair-package.ps1 -Source .\old-package.zip -Output .\old-package.clpkg
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Source,
    [string]$Output,
    [string]$Password,
    [int]$Iterations = 210000,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function ConvertFrom-SecureStringPlain {
    param([Security.SecureString]$Secure)
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
if (-not (Test-Path -LiteralPath $java) -and -not (Get-Command $java -ErrorAction SilentlyContinue)) {
    throw '未找到 java，请设置 JAVA_HOME，或把 java 加入 PATH。'
}

if (-not (Test-Path -LiteralPath $Source)) {
    throw "源路径不存在: $Source"
}
$sourcePath = (Resolve-Path -LiteralPath $Source).Path
$outputPath = if ($Output) { [IO.Path]::GetFullPath($Output) } else { $null }

if ([string]::IsNullOrEmpty($Password)) {
    $first = ConvertFrom-SecureStringPlain (Read-Host -AsSecureString -Prompt '修复包密码')
    $second = ConvertFrom-SecureStringPlain (Read-Host -AsSecureString -Prompt '再次输入修复包密码')
    if ($first -cne $second) {
        throw '两次输入的密码不一致。'
    }
    $Password = $first
} else {
    Write-Warning '通过 -Password 传参会进入命令历史；建议改用交互输入或环境变量。'
}

$javaArgs = @(
    '-Dfile.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8',
    (Join-Path $PSScriptRoot 'PackageTool.java'),
    'pack',
    '--source', $sourcePath,
    '--iterations', "$Iterations"
)
if ($outputPath) { $javaArgs += @('--output', $outputPath) }
if ($Force) { $javaArgs += '--force' }

$env:CASDON_PKG_PASSWORD = $Password
try {
    & $java @javaArgs
    if ($LASTEXITCODE -ne 0) {
        throw "打包失败（退出码 $LASTEXITCODE）。"
    }
} finally {
    Remove-Item Env:CASDON_PKG_PASSWORD -ErrorAction SilentlyContinue
}
