param([ValidateSet(1, 2, 3, 4)][int]$Stage = 1)
$ErrorActionPreference = 'Stop'
$recordPath = Join-Path (Split-Path -Parent $PSScriptRoot) $(if ($Stage -gt 1) { "build\qa$Stage\process.json" } else { 'build\qa\process.json' })
if (-not (Test-Path -LiteralPath $recordPath)) { Write-Output 'No owned QA process.'; exit 0 }
$record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
$process = Get-Process -Id $record.id -ErrorAction SilentlyContinue
if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq ([datetime]$record.started).ToUniversalTime().Ticks) {
    Stop-Process -Id $process.Id
    Write-Output 'Stopped this worktree QA process.'
} else { Write-Output 'Owned process is no longer running; other processes were preserved.' }
