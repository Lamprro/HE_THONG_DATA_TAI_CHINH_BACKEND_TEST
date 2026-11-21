param(
 [Parameter(Mandatory=$true)][string]$ReleaseRoot,
 [string]$Python=$env:LOCAL_AI_PYTHON,
 [int]$Port=8794
)
$ErrorActionPreference='Stop'
if(-not $Python){throw 'Set LOCAL_AI_PYTHON to the verified ML environment python.exe.'}
if(-not $env:LOCAL_AI_SUITE_TOKEN -or $env:LOCAL_AI_SUITE_TOKEN.Length -lt 32){throw 'Set LOCAL_AI_SUITE_TOKEN to a private token of at least 32 characters.'}
$taskRoot=(Resolve-Path -LiteralPath $ReleaseRoot).Path
if(-not (Test-Path -LiteralPath (Join-Path $taskRoot 'release.json'))){throw 'Missing sealed release.json.'}
& $Python -B (Join-Path $taskRoot 'release_server.py') --root $taskRoot --port $Port
if($LASTEXITCODE -ne 0){throw 'Local AI runtime stopped with an error. Inspect its diagnostics.'}
