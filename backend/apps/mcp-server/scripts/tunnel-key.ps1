function Get-CopenhagenTunnelKey {
    param([string]$EnvFile)
    $value = [Environment]::GetEnvironmentVariable('CONTROL_PLANE_API_KEY', 'Process')
    if ([string]::IsNullOrEmpty($value) -and $EnvFile -and (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
        # Parse only the runtime key. Never evaluate shell expressions or import other variables.
        if ((Get-Item -LiteralPath $EnvFile).Length -gt 65536) { throw 'Local tunnel env file is too large.' }
        $found = $false
        foreach ($line in [IO.File]::ReadAllLines($EnvFile, [Text.Encoding]::UTF8)) {
            if ($line -match '^\s*(?:export\s+)?CONTROL_PLANE_API_KEY\s*=\s*(.*?)\s*$') {
                if ($found) { throw 'Duplicate runtime key entry in local env file.' }
                $found = $true
                $value = $Matches[1]
                if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                        ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
            }
        }
        if ($found -and [string]::IsNullOrWhiteSpace($value)) { throw 'Local runtime key entry is empty.' }
    }
    if ($value -and ($value.Length -gt 4096 -or $value -match '[\s"'']')) {
        throw 'Invalid runtime key format. Do not add inline comments or whitespace.'
    }
    return $value
}
