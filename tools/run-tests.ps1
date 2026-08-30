#Requires -Version 5.1
<#
.SYNOPSIS
    Compiles 2M2SCache and runs its JUnit tests using only a JDK - no Maven or Gradle.

.DESCRIPTION
    Mirrors what .github/workflows/build.yml does, so a green local run means a green CI run.
    The JUnit Platform Console Standalone jar is downloaded once into tools/lib/ and reused.

.PARAMETER Class
    Fully qualified test classes to run instead of scanning everything,
    e.g. -Class test.SmallTests

.PARAMETER Method
    A single test method to run, e.g. -Method 'test.SmallTests#testValueUpdate'.

.PARAMETER IncludeDisabled
    Also run @Disabled tests (BigTests, Benchmark). These take many minutes.

.PARAMETER Clean
    Delete the out/ directory before compiling.

.PARAMETER CompileOnly
    Compile production and test sources, then stop.

.EXAMPLE
    .\tools\run-tests.ps1
    Compile everything and run SmallTests + MediumTests.

.EXAMPLE
    .\tools\run-tests.ps1 -Class test.SmallTests
    Fast feedback loop: correctness tests only, no 15-second throughput tests.

.EXAMPLE
    .\tools\run-tests.ps1 -Class test.Benchmark -IncludeDisabled
    Run the benchmarks that are @Disabled in CI.
#>
[CmdletBinding()]
param(
    [string[]] $Class,
    [string]   $Method,
    [switch]   $IncludeDisabled,
    [switch]   $Clean,
    [switch]   $CompileOnly,
    [string]   $JUnitVersion = '6.0.0'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot  = Split-Path -Parent $PSScriptRoot
$JUnitJar  = "tools/lib/junit-platform-console-standalone-$JUnitVersion.jar"
$JUnitUrl  = "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/$JUnitVersion/junit-platform-console-standalone-$JUnitVersion.jar"

function Write-Step { param([string] $Message) Write-Host "==> $Message" -ForegroundColor Cyan }

# Native tools write progress and warnings to stderr, which PowerShell would otherwise
# escalate into a terminating error. Check $script:NativeExitCode instead. The tool's own
# output is left on the success stream so it stays visible and redirectable.
$script:NativeExitCode = 0
function Invoke-Native {
    param([Parameter(Mandatory)] [string] $Exe, [Parameter(Mandatory)] [string[]] $Arguments)

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Exe @Arguments
        $script:NativeExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
}

function Assert-Jdk {
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if (-not $javac) {
        throw "javac was not found on PATH. Install JDK 17 (or newer) and reopen the terminal."
    }

    $reported = (& javac -version 2>&1) -join ' '
    if ($reported -match '(\d+)') {
        $major = [int] $Matches[1]
        if ($major -lt 17) {
            throw "JUnit $JUnitVersion needs Java 17 or newer, but javac reports version $major."
        }
    }
    Write-Step "Using $reported ($($javac.Source))"
}

function Get-JUnitJar {
    if (Test-Path $JUnitJar) { return }

    Write-Step "Downloading junit-platform-console-standalone $JUnitVersion"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $JUnitJar) | Out-Null

    if ([Net.ServicePointManager]::SecurityProtocol -notmatch 'Tls12') {
        [Net.ServicePointManager]::SecurityProtocol =
            [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    }

    $partial = "$JUnitJar.part"
    try {
        Invoke-WebRequest -Uri $JUnitUrl -OutFile $partial -UseBasicParsing
        Move-Item -Force $partial $JUnitJar
    }
    catch {
        if (Test-Path $partial) { Remove-Item -Force $partial }
        throw "Could not download $JUnitUrl : $($_.Exception.Message)"
    }
}

function Invoke-Javac {
    param(
        [Parameter(Mandatory)] [string]   $OutputDir,
        [Parameter(Mandatory)] [string[]] $Sources,
        [string[]] $ClassPath = @()
    )

    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

    $arguments = @('-Xlint:all', '-d', $OutputDir)
    if ($ClassPath.Count -gt 0) {
        $arguments += @('-cp', ($ClassPath -join [IO.Path]::PathSeparator))
    }
    $arguments += $Sources

    Invoke-Native 'javac' $arguments
    if ($script:NativeExitCode -ne 0) { throw 'Compilation failed.' }
}

# Relative paths throughout: the repository lives under a path containing spaces, and
# quoting rules for native commands in Windows PowerShell are unreliable.
Push-Location $RepoRoot
try {
    Assert-Jdk

    if ($Clean -and (Test-Path 'out')) {
        Write-Step 'Cleaning out/'
        Remove-Item -Recurse -Force 'out'
    }

    Get-JUnitJar

    Write-Step 'Compiling production sources -> out/classes'
    $mainSources = Get-ChildItem 'src/concurrent' -Filter '*.java' | ForEach-Object { "src/concurrent/$($_.Name)" }
    Invoke-Javac -OutputDir 'out/classes' -Sources $mainSources

    Write-Step 'Compiling test sources -> out/test-classes'
    $testSources = Get-ChildItem 'src/test' -Filter '*.java' | ForEach-Object { "src/test/$($_.Name)" }
    Invoke-Javac -OutputDir 'out/test-classes' -Sources $testSources -ClassPath @($JUnitJar, 'out/classes')

    if ($CompileOnly) {
        Write-Step 'Compile-only requested, skipping test execution.'
        exit 0
    }

    $runtimeClassPath = @('out/classes', 'out/test-classes') -join [IO.Path]::PathSeparator
    $arguments = @(
        '-ea',
        # CI runs under en_US; pin the locale so the throughput printouts format
        # identically here instead of emitting separators the console cannot render.
        '-Duser.language=en',
        '-Duser.country=US',
        '-Dstdout.encoding=UTF-8',
        '-jar', $JUnitJar,
        'execute',
        "--class-path=$runtimeClassPath",
        '--details=tree',
        '--details-theme=ascii',
        '--reports-dir=out/test-reports',
        '--fail-if-no-tests'
    )

    if ($Method) {
        $arguments += "--select-method=$Method"
    }
    elseif ($Class) {
        $arguments += ($Class | ForEach-Object { "--select-class=$_" })
    }
    else {
        $arguments += '--scan-class-path=out/test-classes'
    }

    if ($IncludeDisabled) {
        # Turns off the extension that honours @Disabled, so BigTests and Benchmark run.
        $arguments += '--config=junit.jupiter.conditions.deactivate=*DisabledCondition'
    }

    Write-Step 'Running tests'
    Invoke-Native 'java' $arguments

    Write-Host ''
    if ($script:NativeExitCode -eq 0) {
        Write-Host 'Tests passed.' -ForegroundColor Green
    }
    else {
        Write-Host "Tests failed (exit code $($script:NativeExitCode)). Reports: out/test-reports" -ForegroundColor Red
    }
    exit $script:NativeExitCode
}
finally {
    Pop-Location
}
