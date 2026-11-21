"""Vietnamese Word handover from measured release evidence."""
import csv,json,sys
from pathlib import Path
from docx import Document
from docx.shared import Inches,Pt,RGBColor,Cm
from docx.oxml import OxmlElement
from docx.oxml.ns import qn

root=Path(sys.argv[1]).resolve();repo=Path.cwd();out=root/'reports';out.mkdir(exist_ok=True)
fin=repo/'artifacts/local-ai/financial-npat-20261023';market=repo/'artifacts/local-ai/suite-20261023'
s=json.loads((fin/'training/summary.json').read_text());models=list(csv.DictReader((market/'reports/model_comparison.csv').open(encoding='utf-8')))
accept=json.loads((root/'acceptance/acceptance_result.json').read_text());business=json.loads((root/'business-run/acceptance_result.json').read_text());assert accept['status']==business['status']=='PASS'
counts={r['object_name']:int(r['rows']) for r in csv.DictReader((root/'database-audit/01_counts.csv').open())};assert counts['predictions']==counts['runs']==218 and counts['financial_facts']==37
doc=Document();sec=doc.sections[0];sec.page_width=Inches(8.5);sec.page_height=Inches(11);sec.top_margin=sec.bottom_margin=Inches(.65);sec.left_margin=sec.right_margin=Inches(.7)
for name,size in [('Normal',11),('Title',22),('Heading 1',17),('Heading 2',12)]:
    st=doc.styles[name];st.font.name='Arial';st.font.size=Pt(size);st.font.color.rgb=RGBColor(0,0,0);st.paragraph_format.space_after=Pt(8);st.paragraph_format.line_spacing=1.08
for name in ['Title','Heading 1','Heading 2']:doc.styles[name].font.bold=True
for b in list(doc.styles.element.iter(qn('w:pBdr'))):b.getparent().remove(b)
def p(t):doc.add_paragraph(t)
def h(t):doc.add_paragraph(t,'Heading 2')
def page(t):doc.add_page_break();doc.add_paragraph(t,'Heading 1')
def num(x,d=2):return f'{float(x):,.{d}f}'.replace(',',' ').replace('.',',')
def pct(x):return num(float(x)*100)+'%'
def table(headers,rows,widths):
    t=doc.add_table(rows=1,cols=len(headers));t.autofit=False;b=OxmlElement('w:tblBorders')
    for edge in ['top','bottom','left','right','insideH','insideV']:
        e=OxmlElement('w:'+edge);e.set(qn('w:val'),'single');e.set(qn('w:sz'),'4');e.set(qn('w:color'),'D9D9D9');b.append(e)
    t._tbl.tblPr.append(b)
    for i,w in enumerate(widths):t.columns[i].width=Cm(w)
    for n,values in enumerate([headers]+rows):
        row=t.rows[0] if n==0 else t.add_row();row._tr.get_or_add_trPr().append(OxmlElement('w:cantSplit'))
        if n==0:row._tr.get_or_add_trPr().append(OxmlElement('w:tblHeader'))
        for i,(c,val) in enumerate(zip(row.cells,values)):
            c.width=Cm(widths[i]);pr=c._tc.get_or_add_tcPr();sh=OxmlElement('w:shd');sh.set(qn('w:fill'),'24445E' if n==0 else ('F3F5F7' if n%2==0 else 'FFFFFF'));pr.append(sh);mar=OxmlElement('w:tcMar')
            for edge in ['top','bottom','left','right']:
                e=OxmlElement('w:'+edge);e.set(qn('w:w'),'80');e.set(qn('w:type'),'dxa');mar.append(e)
            pr.append(mar);va=OxmlElement('w:vAlign');va.set(qn('w:val'),'center');pr.append(va);pa=c.paragraphs[0];pa.paragraph_format.space_after=Pt(0);pa.paragraph_format.line_spacing=1.05
            if i>0 and len(str(val))<25:pa.alignment=1
            r=pa.add_run(str(val));r.font.size=Pt(10)
            if n==0:r.bold=True;r.font.color.rgb=RGBColor(255,255,255)
    doc.add_paragraph().paragraph_format.space_after=Pt(0)

doc.core_properties.title='Báo cáo hoàn thiện bộ mô hình local AI';doc.core_properties.author='Codex hỗ trợ kỹ thuật dự án'
doc.add_paragraph('Báo cáo hoàn thiện bộ mô hình local AI','Title')
p('Dành cho chủ dự án hệ thống phân tích tài chính\nNgày 23 tháng 10 năm 2026 theo giờ Việt Nam')
h('Kết quả của đợt thực hiện')
p('Đã train lại 11 tác vụ, đóng gói 34 file PKL và chạy thành công luồng từ database qua Spring Boot, Python, PKL rồi lưu kết quả vào database. DB chính hiện có 218 dự đoán thật kèm log và validation của gói mới. Phần kỹ thuật đã sẵn sàng cho chạy nghiên cứu và đối chiếu.')
p('Chưa đủ bằng chứng để thay hoàn toàn LLM. Một số mô hình vẫn kém baseline, dữ liệu tài chính ngoài FPT còn thiếu metadata, và chưa có kết quả dự báo tương lai đã đến hạn để chứng minh hiệu quả thực tế. Vì vậy mọi kết quả vẫn mang trạng thái nghiên cứu.')
table(['Phần việc','Kết quả đã xác nhận'],[
 ['Huấn luyện thị trường','9 tác vụ trên 24 mã đủ điều kiện'],
 ['Huấn luyện LNST','2 tác vụ H1 và H2 cho FPT'],
 ['Đóng gói','34 PKL gồm selected, challenger, baseline và bundle model con'],
 ['Dữ liệu LNST','37 quý đã có nguồn; thêm 3 quý còn thiếu'],
 ['Nghiệm thu tích hợp','241 dự đoán trong schema cô lập và 218 trên DB chính'],
 ['Mức triển khai','RESEARCH_ONLY; chưa tự động thay LLM']
],[4.6,13.2])
p('H1 nghĩa là quý kế tiếp; H2 là cách hai quý; H3 và H4 tương tự. Với hướng giá, cả bốn đều so giá cuối quý đích với cùng giá tại quý đầu vào. H2 không phải riêng mức tăng giảm giữa quý thứ nhất và quý thứ hai.')
p('Baseline là cách dự đoán đơn giản dùng làm đối chứng. Challenger là thuật toán đang thử. Selected là phương án được chọn từ giai đoạn phát triển; khi challenger chưa đủ tốt thì selected vẫn là baseline.')

page('1 Dữ liệu đã kiểm tra và bổ sung')
p('Lần này kết nối DB thành công và xuất snapshot mới lúc 00:06:35 ngày 23/10/2026. Toàn bộ pipeline thị trường đã chạy lại từ snapshot này. Không dùng việc train lại trên gần như cùng lịch sử để tuyên bố độ chính xác tăng.')
table(['Nguồn dữ liệu','Tình trạng hiện tại'],[
 ['Giá và thị trường','25 mã trong universe; 24 đủ điều kiện suy luận, HPG bị loại theo kiểm tra dữ liệu'],
 ['Tài chính canonical','279.374 item của 3.900 báo cáo; 0 item đạt hợp đồng strict PIT'],
 ['Ngày công bố và scope','279.374 item còn thiếu ngày công bố và chưa rõ scope'],
 ['Loại kỳ và đơn vị','98.353 item chưa rõ riêng quý hay lũy kế; 142 lỗi đơn vị'],
 ['Fact LNST nguồn riêng','37 quý FPT; 19 nhận tự động và 18 do AI đọc ảnh']
],[5.0,12.8])
h('Ba quý đã khôi phục')
p('Các trang báo cáo bị xoay khiến OCR cũ không đọc đúng. Đã kiểm ảnh gốc và bổ sung FPT 2022Q1, 2022Q2, 2025Q2. LNST lần lượt là 1.538.558.114.474 đồng; 1.561.574.291.434 đồng; 2.740.242.077.853 đồng. Mỗi dòng khớp chính xác tổng phần thuộc cổ đông mẹ và phần không kiểm soát. Đã insert vào bảng fact riêng, giữ raw ID, SHA, trang, link, ngày công bố và scope.')
p('Những dòng đọc bằng AI chưa thay cho người kiểm duyệt độc lập. 37 fact có trạng thái tái dựng từ kho báo cáo hiện tại; chưa chứng nhận từng PDF chính là bản phát hành đầu tiên và chưa từng sửa. Không tự gán metadata của một dòng LNST cho toàn bộ báo cáo canonical.')
h('Vì sao vẫn cần ngày công bố')
p('Model dự báo tại ngày nào chỉ được biết dữ liệu đã công bố đến ngày đó. Ví dụ báo cáo quý II công bố cuối tháng 7 không được coi là đã biết vào 30/6. Nếu chỉ biết ngày mà không biết giờ, bộ fact hiện chờ sang 0 giờ ngày kế tiếp theo giờ Việt Nam. Cần phân biệt ngày kết thúc quý, ngày công bố, ngày crawl và ngày chụp dữ liệu.')
p('FPT có thay đổi phạm vi kế toán năm 2026; so sánh qua năm cần chuỗi cùng phạm vi hợp nhất và lịch sử trình bày lại. Đây còn là hạn chế của bộ train, không được giải quyết chỉ bằng tăng số tham số.')

page('2 Thuật toán và kết quả thị trường')
p('Bộ train sử dụng Logistic Regression cho hướng giá, Ridge cho lợi suất, hồi quy phân vị và các cách phân vị lịch sử cho khoảng dự đoán, cùng mô hình tổng hợp từ dự đoán ngoài mẫu của các model con. Logistic và Ridge có tham số phạt để giảm việc học quá sát tập nhỏ; nhiều cây hoặc nhiều đặc trưng không mặc nhiên tốt hơn.')
p('Bảng dưới là challenger so baseline trên 7 quý hồi cứu từ 2025Q1 đến 2026Q3. Các quý này đã được xem trong các lần nghiên cứu trước, vì vậy chưa phải tập kiểm chứng mới hoàn toàn. Hướng giá dùng BA, tức trung bình tỷ lệ nhận đúng hai chiều; giá trị càng cao càng tốt. Các thước đo sai số còn lại càng thấp càng tốt.')
labels={'direction_h1':'Hướng giá H1','direction_h2':'Hướng giá H2','direction_h3':'Hướng giá H3','direction_h4':'Hướng giá H4','ensemble_h1':'Tổng hợp H1','drawdown_h1':'Drawdown H1','volatility_h1':'Volatility H1','quantile_h1':'P10 P50 P90 H1','return_h1':'Lợi suất H1'}
rows=[]
for x in models:
    direction=x['metric']=='balanced_accuracy';quantile=x['model']=='quantile_h1'
    metric='BA %' if direction else 'Pinball' if quantile else 'MAE điểm %'
    scale=1 if quantile else 100
    rows.append([labels[x['model']],metric,num(float(x['challenger'])*scale,4 if quantile else 2),num(float(x['baseline_value'])*scale,4 if quantile else 2),'Qua' if x['development_gate']=='True' else 'Chưa qua'])
table(['Tác vụ','Thước đo','Challenger','Baseline','Cổng dev'],rows,[4.4,3.2,3.4,3.4,3.4])
h('Các kết quả cần hiểu đúng')
p('H3 nhận đúng trường hợp không tăng 75,41%, nhưng chỉ nhận đúng tăng 23,53%, nên BA chỉ 49,47%. Cân bằng hai chiều vẫn chưa đạt. H4 vượt cổng development nhưng hồi cứu nhận đúng không tăng chỉ 38,18%; chưa đủ ổn định để production.')
p('Drawdown giảm MAE khoảng 3,06% so baseline khi gộp dòng, hoặc 2,84% nếu tính đều từng quý. Đây là ứng viên chạy đối chiếu. Volatility qua development nhưng kém baseline ở hồi cứu. Quantile challenger có khoảng riêng theo mã nhưng chưa vượt cổng; selected vẫn dùng phân vị chung. Không đổi lựa chọn theo kết quả hồi cứu để làm đẹp điểm.')

page('3 Mô hình dự báo lợi nhuận sau thuế')
p('LNST ở đây là tổng lợi nhuận sau thuế hợp nhất của FPT, gồm cả phần không kiểm soát. Đầu ra không phải EPS hoặc riêng LNST thuộc cổ đông công ty mẹ. Model dùng 7 đặc trưng: LNST gần nhất, cùng kỳ, bình quân bốn quý, thay đổi so cùng kỳ, hai biến mùa vụ và số quý có dữ liệu; các giá trị được chuẩn hóa theo lịch sử đã biết.')
p('Train so ba baseline đơn giản, Ridge với ba mức phạt và hồi quy trung vị với hai mức phạt. Development có quý đích đến 2023Q4, hồi cứu từ 2024Q1. Tại mỗi lần fit, chỉ dùng nhãn đã được công bố trước thời điểm dự báo. Cổng yêu cầu giảm MAE ít nhất 2%, thắng ít nhất nửa số quý và có tối thiểu 8 quý development.')
table(['Tác vụ','MAE ứng viên','MAE baseline','WAPE ứng viên','Selected'],[[f'H{x["horizon_quarters"]}',num(x['retrospective']['challenger']['mae_billion_vnd']),num(x['retrospective']['baseline']['mae_billion_vnd']),pct(x['retrospective']['challenger']['wape']),x['selection']['selected']] for x in s],[2,3.5,3.5,3.5,5.3])
p('MAE tính bằng tỷ đồng, trên cùng 9 quý hồi cứu ở mỗi tác vụ. WAPE là tổng sai lệch tuyệt đối chia tổng giá trị thực tế; WAPE 6,84% không có nghĩa mô hình đúng 93,16%.')
h('Cải thiện thật và phần chưa cải thiện')
p('H2 nay có 9 quý development; Ridge A100 giảm MAE 7,44%, thắng 5/9 quý và vượt cổng. Trên 9 quý hồi cứu, Ridge giảm MAE 36,16% so baseline. H1 còn kém baseline, nên giữ Last value. Bổ sung dữ liệu không làm mọi mô hình cùng tốt lên.')
p('So đúng 6 quý chung với bản ngày 21/10, Ridge H2 giảm MAE từ 149,73 xuống 148,64 tỷ đồng, cải thiện 0,73%. Selected H2 cải thiện 41,65% chủ yếu do nay được chọn Ridge thay baseline. Không so trực tiếp bộ 6 quý cũ với 9 quý mới rồi gọi toàn bộ chênh lệch là tiến bộ thuật toán.')
h('Dự đoán đã ghi DB ngày 23 tháng 10')
table(['Quý đích','Selected tỷ đồng','Cách hiểu'],[[x['latest'][0]['target_period'],num(x['latest'][0]['prediction']/1e9),'Nowcast sau khi quý đã kết thúc' if x['horizon_quarters']==1 else 'Dự báo khi quý đang diễn ra'] for x in s],[3,4.8,10])
p('Đầu vào tài chính đã xác minh mới nhất vẫn là 2026Q2. H1 cho Q3 được lưu rõ là nowcast và loại khỏi kiểm chứng dự báo trước. H2 cho Q4 cũng không phải dự báo phát hành trước ngày đầu quý. Cả hai tiếp tục ở trạng thái nghiên cứu.')

page('4 Luồng sử dụng và các bảng trong DB')
p('Luồng hiện đã chạy thật: dữ liệu nguồn được validate → snapshot DB → đặc trưng Java hoặc fact tài chính đúng thời điểm → train và so baseline → đóng gói PKL có SHA → đăng ký manifest và input → Spring gọi Python → kiểm output → ghi dự đoán và log.')
table(['Bảng','Ý nghĩa'],[
 ['local_ai_packages','Lưu phiên bản gói, manifest, SHA và trạng thái nghiên cứu'],
 ['local_ai_package_inputs','Lưu snapshot đặc trưng bất biến theo gói, model và mã'],
 ['local_ai_package_runs','Lưu request, response, validation, HTTP, thời gian, lỗi và cache'],
 ['local_ai_package_predictions','Lưu dự đoán đã validation thành công, quý đích và thời điểm phát hành'],
 ['financial_disclosure_facts','Lưu số liệu tài chính nguồn cùng bằng chứng ngày công bố, scope và tài liệu']
],[6.2,11.6])
p('Bốn bảng đầu phục vụ đăng ký và gọi sau train; không phải toàn bộ dataset huấn luyện. Dataset, các lần chia theo thời gian, nhãn và metrics được giữ trong gói bằng chứng. DB chính đã có 1 package, 218 input, 218 run SUCCEEDED và 218 prediction; tất cả có HTTP 200 và validation PASS.')
h('Cách gọi từ ứng dụng')
p('Endpoint mới là POST /api/admin/local-ai/suite/execute. Request cần packageId, modelId, ticker, role và requestId là UUID. Ví dụ chọn modelId npat_h2, ticker FPT, role selected. Spring lấy input đã đăng ký trong DB; người gọi không phải gửi lại tất cả JSON đang đưa cho LLM.')
p('Request ID giúp chống xử lý trùng. Gửi lại cùng yêu cầu đã thành công trả CACHED và không gọi PKL thêm; đổi nội dung nhưng dùng lại ID bị từ chối. Mỗi lần gọi mới vẫn có log riêng. PKL không tự học từ lần gọi; phải train phiên bản mới khi thêm dữ liệu đã xác minh.')
p('Python chỉ mở cổng loopback và yêu cầu token. Gói được kiểm SHA trước khi nạp. Backend mặc định tắt route suite cho đến khi cấu hình token và bật tính năng. Hướng dẫn khởi động và JSON mẫu đầy đủ nằm trong README của gói bàn giao và tài liệu LOCAL_AI_RELEASE_20261023.md.')

page('5 Kiểm chứng và trạng thái sẵn sàng')
table(['Lớp nghiệm thu','Bằng chứng đã chạy'],[
 ['Backend Java','14 unit tests PASS; unit adapter dùng fixture riêng'],
 ['Thị trường','648 kiểm tra output offline; 99 input sai bị từ chối; 72 HTTP thật trả 648 output'],
 ['LNST','90 kiểm tra PASS, gồm nạp PKL, input contract và bất biến trước dữ liệu tương lai'],
 ['Công thức và thời gian','Python độc lập đối chiếu công thức Java; thay dữ liệu tương lai không đổi đặc trưng quá khứ'],
 ['Spring trong schema cô lập','241 dự đoán thật; auth, hash, cache, conflict, input thiếu và request đồng thời đều PASS'],
 ['Spring trên DB chính','218 dự đoán thật và log đã đọc đối chiếu lại; không provider mock'],
 ['Đánh giá thực tế','217 kết quả chưa đến hạn; 1 nowcast sau quý bị loại khỏi chấm forward']
],[5,12.8])
p('Schema kiểm thử đã được dọn sau khi giữ bằng chứng; DB chính giữ dự đoán thật. Dịch vụ chạy thử đã dừng. Các trường hợp lỗi hoặc request giả định chỉ kiểm trong schema cô lập hoặc unit test, không được chèn thành dự báo nghiệp vụ.')
h('Sẵn sàng ở mức nào')
p('Sẵn sàng nạp PKL, gọi qua API admin và lưu log để nghiên cứu, chạy đối chiếu hoặc tích hợp màn hình nội bộ có nhãn thử nghiệm. Chưa có dịch vụ chạy nền thường trực, chưa tự chuyển routing LLM, và chưa có chứng nhận production về độ chính xác.')
p('Evaluator đã chạy trên cả 218 kết quả. Hệ thống không tự điền actual khi chưa hết quý. Công cụ chấm đối chiếu hiện xuất CSV và receipt ở local; chưa tự ghi outcome của bộ suite mới vào DB. Dự báo trong quý phải được tách khỏi dự báo phát hành trước toàn bộ khoảng mục tiêu.')
h('Nguyên nhân chính còn hạn chế')
p('Các tín hiệu thị trường đã thử không tạo lợi thế ổn định qua nhiều quý. Giỏ hiện tại chỉ có 25 mã, chưa đại diện lịch sử toàn thị trường; giá điều chỉnh và universe là phiên bản hiện tại. Tài chính FPT chỉ có 37 quý và thay đổi phạm vi kế toán. Nhiều chỉ số hơn không bù được sai lệch thời điểm, thiếu nhãn hoặc thiếu số chu kỳ thị trường.')

page('6 Dữ liệu cần hoàn thiện và cách vận hành tiếp')
p('Ưu tiên vận hành drawdown và LNST H2 song song baseline để thu kết quả phát hành thật. Giữ lịch sử bản cũ; chỉ đổi model khi qua cổng trên giai đoạn mới. Tiếp tục dùng baseline cho các tác vụ chưa đạt. Không có cơ sở bảo đảm một mức accuracy cố định bằng cách tăng số lần train.')
table(['Việc cần làm','Thông tin cần bổ sung'],[
 ['Tài chính các công ty','Ngày giờ công bố thật, scope, đơn vị, riêng quý hay lũy kế, bản phát hành đầu và bản sửa, raw và SHA'],
 ['LNST FPT','Duyệt độc lập 18 dòng đọc ảnh; xử lý các quý thiếu và chuỗi cùng phạm vi kế toán'],
 ['Giá và thị trường','Giữ provider và adjustment basis; kiểm phiên thiếu; có lịch sử thành phần universe'],
 ['Các chỉ tiêu tài chính khác','LNTT, tài sản, vốn chủ, nợ cần dataset đúng thời điểm riêng cho từng target'],
 ['Tin tức và macro','Nhãn người duyệt cho NEWS; lịch sử lần công bố và điều chỉnh cho macro']
],[5.0,12.8])
h('Một vòng cập nhật hợp lệ')
p('Lấy dữ liệu qua pipeline Spring và adapter hiện có; validate và lưu nguồn. Chạy pipeline train ra thư mục mới, so baseline theo thời gian, kiểm rò rỉ tương lai và nạp PKL. Tạo package ID mới, nghiệm thu schema cô lập, đăng ký DB rồi gọi dự đoán. Khi quý đích và báo cáo thực tế đã có, chạy evaluator trên snapshot mới.')
p('Thêm doanh thu, biên lợi nhuận, dòng tiền hoặc tin tức chỉ sau khi biết chúng được công bố lúc nào và cùng phạm vi kế toán. Các công thức xác định như tỷ lệ và chuyển đổi đơn vị tiếp tục tính ở Java. Local AI đảm nhiệm từng trường định lượng; phần giải thích hoặc tổng hợp tin tức của LLM cần bộ nhãn và nghiệm thu riêng.')
h('Tệp bàn giao và nguồn kiểm tra')
p('Gói Python: HE_THONG_DATA_TAI_CHINH_AI_LOCAL/local_ai_release_20261023. Bắt đầu bằng README.md; báo cáo này ở reports. Source train và công cụ chạy nằm trong source; evidence giữ dữ liệu, metrics, log nghiệm thu và kết quả dự đoán.')
p('Backend: docs/LOCAL_AI_RELEASE_20261023.md; migration V20261023_01__local_ai_suite_runtime.sql; service, contract và controller LocalAiSuite. Bằng chứng DB nằm ở database-audit; kết quả chấm đến hạn nằm ở outcome-review. Toàn bộ số trong báo cáo lấy từ các lần chạy ngày 23/10/2026, không dùng dữ liệu giả để chứng minh tích hợp.')
p('Nguồn tài chính là PDF và danh mục công bố chính thức FPT đã được pipeline lưu raw. Các fact giữ URL và SHA để truy ngược từng dòng. Các phần còn thiếu được giữ trạng thái chặn hoặc nghiên cứu; chưa tạo model giả cho các target chưa đủ dữ liệu.')
footer=sec.footer.paragraphs[0];footer.alignment=2;r=footer.add_run('Local AI  |  23 10 2026  |  Trang ');r.font.name='Arial';r.font.size=Pt(9);f=OxmlElement('w:fldSimple');f.set(qn('w:instr'),'PAGE');footer._p.append(f)
path=out/'Bao_cao_hoan_thien_Local_AI_20261023.docx';doc.save(path);print(path)
