param([string]$JavaHome = $env:JAVA_HOME, [ValidateSet(1, 2, 3, 4)][int]$Stage = 1)
$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = [IO.Path]::GetFullPath((Join-Path $moduleRoot '..\..'))
$port = if ($Stage -ge 3) { 8093 } else { 8090 + $Stage }
$runtimeRoot = Join-Path $moduleRoot $(if ($Stage -gt 1) { "build\qa$Stage" } else { 'build\qa' })
$catalogFile = Join-Path $runtimeRoot 'catalog.json'
if ($Stage -gt 1 -and -not (Test-Path -LiteralPath $catalogFile)) {
    throw "Import the supplied catalog to build/qa$Stage/catalog.json before starting QA$Stage."
}
if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
    throw "Port $port is already in use. Stop the owned QA server for its running Stage first. QA3/4 share 8093."
}
if (-not $JavaHome) {
    $jdkRoot = Join-Path $env:USERPROFILE '.gradle\jdks'
    $jdk = Get-ChildItem -LiteralPath $jdkRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '-21-' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe')) } |
        Select-Object -First 1
    if ($jdk) { $JavaHome = $jdk.FullName }
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Set JAVA_HOME to a Java 21 JDK, or pass -JavaHome.'
}
$env:JAVA_HOME = $JavaHome
Push-Location $backendRoot
try {
    & .\gradlew.bat :apps:mcp-server:writeQaClasspath --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'QA build failed.' }
} finally { Pop-Location }
$classPath = (Get-Content -LiteralPath (Join-Path $moduleRoot 'build\qa\classpath.txt') -Raw).Trim().Replace('\', '/')
$argFile = Join-Path $runtimeRoot 'java.args'
$mainClass = switch ($Stage) {
    4 { 'com.copenhagen.mcp.qa.LocalHandoffQa' }
    3 { 'com.copenhagen.mcp.qa.LocalCartQa' }
    2 { 'com.copenhagen.mcp.qa.LocalCatalogQa' }
    default { 'com.copenhagen.mcp.qa.LocalMcpQa' }
}
$catalogArgument = if ($Stage -gt 1) { '"-Dcopenhagen.qa.catalog=' + $catalogFile.Replace('\', '/') + '"' + "`n" } else { '' }
if ($Stage -ge 3) {
    $cartFile = Join-Path $runtimeRoot 'cart.json'
    $catalogArgument += '"-Dcopenhagen.qa.cart=' + $cartFile.Replace('\', '/') + '"' + "`n"
}
$arguments = '-Dfile.encoding=UTF-8' + "`n" + $catalogArgument + "-cp`n" + '"' + $classPath + '"' + "`n$mainClass`n"
[IO.File]::WriteAllText($argFile, $arguments, [Text.UTF8Encoding]::new($false))
$process = Start-Process -FilePath (Join-Path $JavaHome 'bin\java.exe') -ArgumentList ('@"' + $argFile + '"') `
    -WorkingDirectory $backendRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $runtimeRoot 'server.out.log') `
    -RedirectStandardError (Join-Path $runtimeRoot 'server.err.log')
@{ id = $process.Id; started = $process.StartTime.ToUniversalTime().ToString('o') } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtimeRoot 'process.json') -Encoding UTF8
try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($process.HasExited) { throw 'QA process exited. See build/qa/server logs.' }
        try {
            $result = Invoke-RestMethod -Uri "http://127.0.0.1:$port/qa/check" -TimeoutSec 5
            if (-not $result.isError -and $result.structuredContent.service -eq 'copenhagen-mcp') {
                $ready = $true
                Write-Output ("QA$Stage READY | connection-version=" + $result.structuredContent.uiVersion)
                break
            }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'QA startup verification failed. See build/qa/server logs.' }
    Write-Output "Preview: http://127.0.0.1:$port/qa | MCP: http://127.0.0.1:$port/mcp"
} catch {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id }
    throw
}
