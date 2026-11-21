"""Build immutable research runtime package, never activate or overwrite a deployed model."""
import argparse,json,shutil,uuid
from pathlib import Path
import pandas as pd
from train import dump,sha,now

HERE=Path(__file__).resolve().parent
def main(root):
 out=root/'runtime_package';out.mkdir(exist_ok=False);summaries=[];runtime_files={}
 for name in ['serve.py','model_runtime.py','estimators.py']:
  shutil.copy2(HERE/name,out/name)
 requirements='numpy==2.5.3\npandas==2.3.3\nscikit-learn==1.9.1\nscipy==1.18.1\nthreadpoolctl==3.7.0\n'
 (out/'requirements.txt').write_text(requirements,encoding='utf-8')
 for folder in sorted((root/'models').iterdir()):
  if not folder.is_dir():continue
  summary=json.loads((folder/'training/summary.json').read_text());summaries.append(summary);target=out/'models'/folder.name;(target/'training').mkdir(parents=True)
  shutil.copy2(folder/'feature_contract.json',target/'feature_contract.json');contract=json.loads((folder/'feature_contract.json').read_text());d=pd.read_csv(folder/'dataset.csv');as_of=json.loads((folder/'training/selected.manifest.json').read_text())['fit_as_of']
  d.loc[d.cutoff==as_of,['ticker','cutoff','input_hash',*contract['features']]].to_csv(target/'latest_features.csv',index=False)
  for file in (folder/'training').iterdir():
   if file.suffix=='.pkl' or file.name.endswith('.manifest.json'):shutil.copy2(file,target/'training'/file.name)
  shutil.copy2(folder/'training/summary.json',target/'summary.json')
 for p in out.rglob('*'):
  if p.is_file():runtime_files[p.relative_to(out).as_posix()]=sha(p)
 root_plan=json.loads((root/'suite_plan_before_training.json').read_text());registry=dict(schema='local_ai.research.registry.v1',created_at=now(),fit_as_of=root_plan['fit_as_of'],snapshot_time=(root/'data/snapshot/snapshot_time.txt').read_text().strip(),models=[s['model_id'] for s in summaries],runtime_files=runtime_files,production_eligible=False,replace_llm=False,selection_meaning='Development choice only; selected may be a constant or momentum baseline',execution_scope='Frozen snapshot CLI/loopback research API, SQLite ledger only. No new Spring or business DB integration.',historical_exposure='All prior periods exposed; no untouched future test')
 dump(out/'registry.json',registry);dump(root/'suite_summary.json',summaries)
 ticker=pd.read_csv(out/'models/direction_h1/latest_features.csv').ticker.iloc[0];dump(out/'example_request.json',dict(schema='local_ai.suite.batch.v1',request_id=str(uuid.uuid4()),cutoff=registry['fit_as_of'],tickers=[ticker]))
 (out/'README.md').write_text('''# Bộ mô hình local AI nghiên cứu

Đã train và đóng gói file thật. Mọi kết quả là RESEARCH_ONLY; selected là cấu hình chọn ở development, có thể là baseline. Không tự thay LLM, không tự học khi predict, không nối các model mới với Spring/DB nghiệp vụ. Chỉ nạp pickle tin cậy từ dự án; checksum không làm pickle lạ an toàn.

## Chạy thử

Dùng Python 3.13 và đúng các thư viện trong requirements.txt. Từ thư mục gói:

```powershell
python serve.py --root . --input example_request.json --output result.json --ledger local-research.sqlite
```

Đổi tickers/request_id trong example_request.json. Chỉ nhận mã có đủ đặc trưng ở tất cả model và cutoff đã đóng băng. Kết quả có các horizon/target_period riêng: H2/H3/H4 so giá cuối kỳ với cùng giá tại origin, không phải tăng/giảm riêng từng quý ở giữa.

## API

Đặt LOCAL_AI_RESEARCH_TOKEN riêng tư, tối thiểu32ký tự. Chạy `python serve.py --root . --port 8788 --ledger local-research.sqlite`. POST http://127.0.0.1:8788/predict, Authorization Bearer, body như example_request.json. Không mở cổng public. Lặp request_id cùng dữ liệu dùng cache; khác dữ liệu bị từ chối. SQLite chỉ là audit local, không phải bảng backend.

Muốn dùng quý mới phải chạy lại pipeline Java với dữ liệu đã qua ingestion/validation/builder của Spring, tạo snapshot/version mới và kiểm định lại. Không đổi ngày request để ép gói cũ dự đoán quý mới. Xem báo cáo ngoài gói về cổng chất lượng, mô hình chưa thắng baseline và các đầu ra tài chính còn thiếu nguồn.
''',encoding='utf-8')
 # Include instructions/example in the integrity inventory as well.
 for name in ['README.md','example_request.json']:registry['runtime_files'][name]=sha(out/name)
 dump(out/'registry.json',registry);return out
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);a=p.parse_args();main(a.root.resolve())
