$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'tunnel-key.ps1')
$previous = [Environment]::GetEnvironmentVariable('CONTROL_PLANE_API_KEY', 'Process')
$temporaryFile = [IO.Path]::GetTempFileName()
function Assert-Key([bool]$Condition) { if (-not $Condition) { throw 'Tunnel key test failed.' } }
function Assert-Rejected {
    $rejected = $false
    try { $null = Get-CopenhagenTunnelKey -EnvFile $temporaryFile } catch { $rejected = $true }
    Assert-Key $rejected
}
try {
    [Environment]::SetEnvironmentVariable('CONTROL_PLANE_API_KEY', $null, 'Process')
    [IO.File]::WriteAllText($temporaryFile, "# local fixture`nCONTROL_PLANE_API_KEY='fixture-only-key'`nOTHER=unused")
    Assert-Key ((Get-CopenhagenTunnelKey -EnvFile $temporaryFile) -eq 'fixture-only-key')
    $env:CONTROL_PLANE_API_KEY = 'environment-fixture-key'
    Assert-Key ((Get-CopenhagenTunnelKey -EnvFile $temporaryFile) -eq 'environment-fixture-key')
    [Environment]::SetEnvironmentVariable('CONTROL_PLANE_API_KEY', $null, 'Process')
    [IO.File]::WriteAllText($temporaryFile, "CONTROL_PLANE_API_KEY=one`nCONTROL_PLANE_API_KEY=two")
    Assert-Rejected
    [IO.File]::WriteAllText($temporaryFile, 'CONTROL_PLANE_API_KEY=')
    Assert-Rejected
    [IO.File]::WriteAllText($temporaryFile, 'CONTROL_PLANE_API_KEY=value # inline-comment')
    Assert-Rejected
    [IO.File]::WriteAllText($temporaryFile, 'CONTROL_PLANE_API_KEY=$(throw)')
    Assert-Key ((Get-CopenhagenTunnelKey -EnvFile $temporaryFile) -eq '$(throw)')
    [IO.File]::WriteAllText($temporaryFile, '# no runtime key')
    Assert-Key ([string]::IsNullOrEmpty((Get-CopenhagenTunnelKey -EnvFile $temporaryFile)))
    Write-Output 'Tunnel key tests passed: precedence, quotes, duplicate/empty rejection, no evaluation, missing key.'
} finally {
    [Environment]::SetEnvironmentVariable('CONTROL_PLANE_API_KEY', $previous, 'Process')
    Remove-Item -LiteralPath $temporaryFile
    $previous = $null
}
