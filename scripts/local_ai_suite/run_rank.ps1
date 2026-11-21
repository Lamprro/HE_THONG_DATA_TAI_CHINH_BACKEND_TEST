param(
    [Parameter(Mandatory=$true)][string]$Output,
    [string]$BusinessDate=(Get-Date -Format 'yyyy-MM-dd'),
    [string]$Python=$env:LOCAL_AI_PYTHON,
    [string]$Jdbc="$env:USERPROFILE/.m2/repository/org/postgresql/postgresql/42.7.5/postgresql-42.7.5.jar",
    [string]$Config,
    [string]$SnapshotRun,
    [string]$ReadinessJson,
    [ValidateRange(1,4)][int]$Workers=1
)
$ErrorActionPreference='Stop'
$backend=(Resolve-Path "$PSScriptRoot/../..").Path
if (-not $Python) {
    $Python=Join-Path (Split-Path $backend -Parent) 'HE_THONG_DATA_TAI_CHINH_AI_LOCAL/.venv/Scripts/python.exe'
}
if (-not $Config) { $Config=Join-Path $backend 'application-local.properties' }
if (-not $SnapshotRun) {
    $SnapshotRun="$Output-snapshot"
    & $Python -B "$PSScriptRoot/snapshot_rank.py" --backend $backend --config $Config --jdbc $Jdbc --out $SnapshotRun
    if ($LASTEXITCODE -ne 0) { throw 'Read-only snapshot export failed' }
}
$rankArgs=@('--snapshot-run',$SnapshotRun,'--out',$Output,'--business-date',$BusinessDate,'--workers',$Workers)
if ($ReadinessJson) { $rankArgs += @('--readiness-json',$ReadinessJson) }
& $Python -B "$PSScriptRoot/train_rank.py" @rankArgs
if ($LASTEXITCODE -ne 0) { throw 'Rank training failed' }
