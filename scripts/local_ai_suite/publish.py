"""Copy a verified suite to a NEW destination, checksum every file and run the destination package."""
import argparse,json,shutil,subprocess,sys,zipfile
from pathlib import Path
from train import dump,sha,now

HERE=Path(__file__).resolve().parent
def main(root,destination):
 state=json.loads((root/'status.json').read_text());qa=json.loads((root/'reports/document_verification.json').read_text())
 if state['status']!='VERIFIED_RESEARCH_PACKAGE' or qa['status']!='PASS' or qa['docx_sha256']!=sha(root/'reports'/qa['file']):raise ValueError('UNVERIFIED_SUITE_OR_DOCUMENT')
 if destination.exists():raise ValueError('DESTINATION_EXISTS_NO_OVERWRITE')
 destination.mkdir(parents=True);copied=[]
 def copy(src,relative):
  dst=destination/relative;dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(src,dst)
  if sha(src)!=sha(dst):raise ValueError('COPY_SHA_MISMATCH')
  copied.append(dict(path=relative.as_posix(),sha256=sha(dst)))
 for folder in ['source','data','models','runtime_package']:
  for src in (root/folder).rglob('*'):
   if src.is_file() and '__pycache__' not in src.parts:copy(src,src.relative_to(root))
 for src in (root/'reports').iterdir():
  if src.is_file() and src.suffix not in ['.sqlite'] and not src.name.endswith('.tmp'):copy(src,src.relative_to(root))
 for folder in ['verification','verification_future','forward_evaluation_now','db-retry']:
  for src in (root/folder).glob('*'):
   if src.is_file() and src.suffix in ['.json','.log','.csv']:copy(src,src.relative_to(root))
 for src in root.glob('*'):
  if src.is_file() and src.suffix in ['.json','.log']:copy(src,src.relative_to(root))
 for src in HERE.rglob('*'):
  if src.is_file() and '__pycache__' not in src.parts:copy(src,Path('tools')/src.relative_to(HERE))
 # Tools are current complete operational sources; source/ remains the immutable train-time snapshot.
 demo='''param([string[]]$Tickers=@('FPT'),[string]$Python,[switch]$Challenger)
$ErrorActionPreference='Stop'
if(-not $Python){$Python=Join-Path $PSScriptRoot '../.venv/Scripts/python.exe'}
if(-not (Test-Path -LiteralPath $Python)){throw 'Python environment not found. Set -Python or restore the existing ML environment.'}
$taskStamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$taskOut=Join-Path $PSScriptRoot "demo/$taskStamp"
New-Item -ItemType Directory -Path $taskOut | Out-Null
$taskRequest=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'runtime_package/example_request.json') | ConvertFrom-Json
$taskRequest.request_id=[guid]::NewGuid().ToString()
$taskRequest.tickers=@($Tickers)
[System.IO.File]::WriteAllText((Join-Path $taskOut 'request.json'),($taskRequest | ConvertTo-Json -Depth 8),[System.Text.UTF8Encoding]::new($false))
$taskRole=if($Challenger){'challenger'}else{'selected'}
& $Python (Join-Path $PSScriptRoot 'runtime_package/serve.py') --root (Join-Path $PSScriptRoot 'runtime_package') --role $taskRole --input (Join-Path $taskOut 'request.json') --output (Join-Path $taskOut 'result.json') --ledger (Join-Path $PSScriptRoot 'demo/local-research.sqlite')
if($LASTEXITCODE -ne 0){throw 'Inference failed. See the local ledger and input error.'}
Write-Output "RESULT: $taskOut/result.json"
'''
 (destination/'RUN_DEMO.ps1').write_text(demo,encoding='utf-8')
 (destination/'README.md').write_text('''# Bộ local AI ngày 21 tháng 10 năm 2026

Đọc báo cáo Word trong reports trước khi dùng kết quả. Đã train9task và đóng gói28pkl; đây là bộ nghiên cứu, chưa thay LLM hoặc chứng nhận production. Selected có thể là baseline. Dữ liệu snapshot20/10, không phải DB vừa refresh21/10.

Chạy `./RUN_DEMO.ps1 -Tickers FPT,ACB,VNM` bằng Python environment sẵn có. Chỉ dùng role challenger khi muốn xem ứng viên nghiên cứu và quantile khác nhau theo mã. Ví dụ `./RUN_DEMO.ps1 -Tickers FPT -Challenger`. Script không train hoặc ghi DB nghiệp vụ; có log SQLite local và output mới theo giờ chạy.

runtime_package có API loopback, requirements, pickle và manifest. models có từng task và evidence. source là mã đóng băng lúc train; tools là bộ công cụ hoàn chỉnh hiện hành. Đọc tools/README.md để chạy lại pipeline với `-BackendRoot` là repo Java thật. Không dùng source snapshot cũ như thể là DB hiện tại hoặc pickle này như model tự học.

reports/predictions_by_symbol.csv chứa24mã, role selected; conditional_quantiles_by_symbol.csv giữ đủ3role. All H1–H4 so endpoint với origin30/09/2026. Dự báo phát hành21/10 khi Q4 đã bắt đầu. Drawdown là shadow candidate, các tác vụ còn lại research; tài chính cần thêm metadata để train đúng thời điểm.
''',encoding='utf-8')
 receipts=[]
 for role in ['selected','challenger','baseline']:
  target=destination/'destination-verification';target.mkdir(exist_ok=True);output=target/(role+'.json')
  result=subprocess.run([sys.executable,str(destination/'runtime_package/serve.py'),'--root',str(destination/'runtime_package'),'--role',role,'--input',str(destination/'reports/all_symbols_request.json'),'--output',str(output)],capture_output=True,text=True)
  if result.returncode:raise ValueError('DESTINATION_RUNTIME_FAILED_'+role)
  response=json.loads(output.read_text());assert len(response['results'])==24 and all(len(r['predictions'])==9 for r in response['results']);receipts.append(dict(role=role,predictions=216,output_sha256=sha(output)))
 archive=destination/'Local_AI_9_models_research_20261021.zip'
 with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
  for file in (destination/'runtime_package').rglob('*'):
   if file.is_file() and '__pycache__' not in file.parts:z.write(file,file.relative_to(destination).as_posix())
 with zipfile.ZipFile(archive) as z:
  if z.testzip() is not None:raise ValueError('ZIP_CORRUPT')
 receipt=dict(status='PASS',published_at=now(),destination=str(destination),copied_files=len(copied),files=copied,destination_CLI=receipts,archive_sha256=sha(archive),production_eligible=False,replace_llm=False,old_versions_overwritten=False,business_DB_writes=0)
 dump(destination/'publication_receipt.json',receipt);dump(root/'publication_receipt.json',{k:v for k,v in receipt.items() if k!='files'});print(json.dumps({k:v for k,v in receipt.items() if k!='files'}));return destination
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--destination',type=Path,required=True);a=p.parse_args();main(a.root.resolve(),a.destination.resolve())
