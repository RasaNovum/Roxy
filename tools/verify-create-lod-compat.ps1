param(
    [Parameter(Mandatory = $true)][string]$ModsDirectory,
    [string]$JavaHome = 'C:/Program Files/Java/jdk-21.0.11'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
function Find-Mod([string]$Pattern) {
    $modMatches = @(Get-ChildItem -LiteralPath $ModsDirectory -File | Where-Object { $_.Name -match $Pattern })
    if ($modMatches.Count -ne 1) { throw "Expected exactly one mod matching $Pattern in $ModsDirectory" }
    return $modMatches[0].FullName
}
$create = Find-Mod '^create-\d.*\.jar$'
$iris = Find-Mod '^iris-neoforge-.*\.jar$'
$colorwheel = Find-Mod '^colorwheel-neoforge-.*\.jar$'
$voxy = Find-Mod '^voxy-.*\.jar$'
Push-Location $repo
try {
    $env:JAVA_HOME = $JavaHome
    & './gradlew.bat' -I tools/import-verification.init.gradle :1.21.1:classes :1.21.1:writeImportVerificationClasspath --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Create LOD verification preparation failed' }
    $classpath = ((Get-Content -LiteralPath 'build/import-verification-classpath.txt') |
        Where-Object { $_ -notmatch 'natives-.*(arm64|x86|linux|macos)' }) -join ';'
    $output = Join-Path $repo ('build/create-lod-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $output | Out-Null
    $sources = @('VerifyCreateStaticCompat.java', 'VerifyCreateLodBindings.java', 'VerifyLodEntityOcclusion.java',
        'com/simibubi/create/content/kinetics/belt/BeltBlock.java') | ForEach-Object { Join-Path $PSScriptRoot $_ }
    & "$JavaHome/bin/javac.exe" -proc:none -cp $classpath -d $output @sources
    if ($LASTEXITCODE -ne 0) { throw 'Create LOD verification compilation failed' }
    & "$JavaHome/bin/java.exe" -ea -cp "$output;$classpath" VerifyCreateStaticCompat $create
    if ($LASTEXITCODE -ne 0) { throw 'Static Create geometry verification failed' }
    & "$JavaHome/bin/java.exe" -ea -cp "$output;$classpath" VerifyCreateLodBindings $repo $iris $colorwheel $create
    if ($LASTEXITCODE -ne 0) { throw 'Iris/Colorwheel artifact verification failed' }
    & "$JavaHome/bin/java.exe" -ea -cp "$output;$classpath" VerifyLodEntityOcclusion
    if ($LASTEXITCODE -ne 0) { throw 'LOD entity occlusion GPU verification failed' }
} finally {
    Pop-Location
}
