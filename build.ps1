$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$bundledGradle = Join-Path (Split-Path $taskRoot -Parent) '.android-tools/gradle-8.11.1/bin/gradle.bat'
Push-Location -LiteralPath $taskRoot
try {
    if (Test-Path -LiteralPath $bundledGradle) {
        & $bundledGradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
    } else {
        & (Join-Path $taskRoot 'gradlew.bat') :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
    }
    if ($LASTEXITCODE -ne 0) { throw '构建或测试失败，请检查上方输出' }
} finally { Pop-Location }
