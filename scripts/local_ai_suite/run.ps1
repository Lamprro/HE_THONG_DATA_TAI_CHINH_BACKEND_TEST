param(
 [string]$BackendRoot=(Resolve-Path "$PSScriptRoot/../.."),
 [string]$Python=$env:LOCAL_AI_PYTHON,
 [string]$Jdbc="$env:USERPROFILE/.m2/repository/org/postgresql/postgresql/42.7.5/postgresql-42.7.5.jar",
 [string]$Output,
 [string]$BusinessDate=(Get-Date -Format 'yyyy-MM-dd'),
 [string]$SnapshotFrom
)
$ErrorActionPreference='Stop'
if(-not $Python){$Python=Join-Path (Split-Path $BackendRoot -Parent) 'HE_THONG_DATA_TAI_CHINH_AI_LOCAL/.venv/Scripts/python.exe'}
if(-not $Output){$Output=Join-Path $BackendRoot ('artifacts/local-ai/suite-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))}
$taskArgs=@('--out',$Output,'--backend',$BackendRoot,'--jdbc',$Jdbc,'--business-date',$BusinessDate)
if($SnapshotFrom){$taskArgs+=@('--snapshot-from',$SnapshotFrom)}
& $Python "$PSScriptRoot/pipeline.py" @taskArgs
if($LASTEXITCODE -ne 0){throw 'Snapshot/features/training failed. Downstream steps were not executed.'}
foreach($taskStage in @('package.py','verify.py')){
 & $Python "$PSScriptRoot/$taskStage" --root $Output
 if($LASTEXITCODE -ne 0){throw "Stage failed: $taskStage"}
}
foreach($taskStage in @('verify_market.py','verify_future.py')){
 & $Python "$PSScriptRoot/$taskStage" $Output
 if($LASTEXITCODE -ne 0){throw "Stage failed: $taskStage"}
}
& $Python "$PSScriptRoot/analyze.py" --root $Output
if($LASTEXITCODE -ne 0){throw 'Final analysis failed. Package not certified.'}
Write-Output "VERIFIED_RESEARCH_PACKAGE: $Output"
