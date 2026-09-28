param(
    [Parameter(Mandatory=$true)][string]$PgBin,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Maven = 'mvn',
    [ValidateRange(1,16384)][int]$TargetMiB = 100,
    [ValidateRange(1,20)][int]$Runs = 3,
    [ValidateRange(0,5)][int]$Warmups = 1,
    [string]$Methods = 'NONE,GZIP,ZSTD,LZ4,BZIP2'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pgDirectory = (Resolve-Path -LiteralPath $PgBin).Path
foreach ($tool in @('initdb.exe','pg_ctl.exe','pg_dump.exe','psql.exe')) {
    if (!(Test-Path -LiteralPath (Join-Path $pgDirectory $tool))) { throw "Missing PostgreSQL tool: $tool" }
}
$originalPath = $env:PATH
$originalJava = $env:JAVA_HOME
Push-Location $projectRoot
try {
    if ($JavaHome) { $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path }
    $env:PATH = "$pgDirectory;$env:PATH"
    $javaExecutable = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
    & $Maven -B -Pbenchmark test-compile
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark compilation failed' }
    $dependencies = (Get-Content -Raw -LiteralPath 'target/benchmark-classpath.txt').Trim()
    $classPath = "target/test-classes;target/classes;$dependencies"
    & $javaExecutable -Xmx256m -cp $classPath com.backuputil.benchmark.PostgresBenchmark $pgDirectory $TargetMiB $Runs $Warmups $Methods
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark failed; inspect the run logs under target/benchmarks' }
} finally {
    Pop-Location
    $env:PATH = $originalPath
    $env:JAVA_HOME = $originalJava
}
