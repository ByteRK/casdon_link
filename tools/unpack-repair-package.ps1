#Requires -Version 7.0
<#
.SYNOPSIS
    解密 .clpkg，并还原为 ZIP 或解包目录。

.DESCRIPTION
    内部调用 tools/PackageTool.java（JDK 11+ 单文件源码运行）。
    --output-dir 与 --output-zip 只能二选一；都不给时默认输出到
    <包名>_unpacked/ 目录。密码优先使用 -Password，否则交互输入。

.EXAMPLE
    pwsh tools/unpack-repair-package.ps1 -InputPath .\my-package.clpkg -OutputDirectory .\my-package

.EXAMPLE
    pwsh tools/unpack-repair-package.ps1 -InputPath .\my-package.clpkg -OutputZip .\my-package.zip
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$InputPath,
    [string]$OutputDirectory,
    [string]$OutputZip,
    [string]$Password,
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
if (-not (Test-Path -LiteralPath $InputPath)) {
    throw "输入文件不存在: $InputPath"
}
if ($OutputDirectory -and $OutputZip) {
    throw '-OutputDirectory 与 -OutputZip 只能二选一。'
}

$inputFull = (Resolve-Path -LiteralPath $InputPath).Path
if ([string]::IsNullOrEmpty($Password)) {
    $Password = ConvertFrom-SecureStringPlain (Read-Host -AsSecureString -Prompt '修复包密码')
} else {
    Write-Warning '通过 -Password 传参会进入命令历史；建议改用交互输入或环境变量。'
}

$javaArgs = @(
    '-Dfile.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8',
    (Join-Path $PSScriptRoot 'PackageTool.java'),
    'unpack',
    '--input', $inputFull
)
if ($OutputDirectory) {
    $javaArgs += @('--output-dir', [IO.Path]::GetFullPath($OutputDirectory))
}
if ($OutputZip) {
    $javaArgs += @('--output-zip', [IO.Path]::GetFullPath($OutputZip))
}
if ($Force) { $javaArgs += '--force' }

$env:CASDON_PKG_PASSWORD = $Password
try {
    & $java @javaArgs
    if ($LASTEXITCODE -ne 0) {
        throw "解包失败（退出码 $LASTEXITCODE）。"
    }
} finally {
    Remove-Item Env:CASDON_PKG_PASSWORD -ErrorAction SilentlyContinue
}
