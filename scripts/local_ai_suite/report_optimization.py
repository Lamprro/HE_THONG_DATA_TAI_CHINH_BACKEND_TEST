"""Evidence-based Word comparison of metadata and the causal optimization experiment."""
import csv,json,sys
from decimal import Decimal
from pathlib import Path
from docx import Document
from docx.shared import Inches,Pt,RGBColor,Cm
from docx.oxml import OxmlElement
from docx.oxml.ns import qn

root=Path(sys.argv[1]).resolve();out=root/'reports';out.mkdir(exist_ok=True)
comparison=json.loads((root/'comparison.json').read_text());proof=json.loads((root/'comparison_verification.json').read_text());assert proof['status']=='PASS'
assert json.loads((root/'candidate/status.json').read_text())['status']=='VERIFIED_RESEARCH_PACKAGE'
meta=json.loads((root/'reconcile-apply.json').read_text());assert meta['status']=='PASS'
doc=Document();sec=doc.sections[0];sec.page_width=Inches(8.5);sec.page_height=Inches(11);sec.top_margin=sec.bottom_margin=Inches(.65);sec.left_margin=sec.right_margin=Inches(.7)
for name,size in [('Normal',11),('Title',22),('Heading 1',17),('Heading 2',12)]:
 st=doc.styles[name];st.font.name='Arial';st.font.size=Pt(size);st.font.color.rgb=RGBColor(0,0,0);st.paragraph_format.space_after=Pt(8);st.paragraph_format.line_spacing=1.08
for name in ['Title','Heading 1','Heading 2']:doc.styles[name].font.bold=True
for b in list(doc.styles.element.iter(qn('w:pBdr'))):b.getparent().remove(b)
def p(t):doc.add_paragraph(t)
def h(t):doc.add_paragraph(t,'Heading 2')
def page(t):doc.add_page_break();doc.add_paragraph(t,'Heading 1')
def num(v,n=2):return f'{float(v):,.{n}f}'.replace(',',' ').replace('.',',')
def table(headers,rows,widths):
 t=doc.add_table(rows=1,cols=len(headers));t.autofit=False;b=OxmlElement('w:tblBorders')
 for edge in ['top','bottom','left','right','insideH','insideV']:
  e=OxmlElement('w:'+edge);e.set(qn('w:val'),'single');e.set(qn('w:sz'),'4');e.set(qn('w:color'),'D9D9D9');b.append(e)
 t._tbl.tblPr.append(b)
 for i,w in enumerate(widths):t.columns[i].width=Cm(w)
 for n,values in enumerate([headers]+rows):
  row=t.rows[0] if n==0 else t.add_row();row._tr.get_or_add_trPr().append(OxmlElement('w:cantSplit'))
  if n==0:row._tr.get_or_add_trPr().append(OxmlElement('w:tblHeader'))
  for i,(c,v) in enumerate(zip(row.cells,values)):
   c.width=Cm(widths[i]);pr=c._tc.get_or_add_tcPr();sh=OxmlElement('w:shd');sh.set(qn('w:fill'),'24445E' if n==0 else ('F3F5F7' if n%2==0 else 'FFFFFF'));pr.append(sh);m=OxmlElement('w:tcMar')
   for edge in ['top','bottom','left','right']:
    e=OxmlElement('w:'+edge);e.set(qn('w:w'),'80');e.set(qn('w:type'),'dxa');m.append(e)
   pr.append(m);va=OxmlElement('w:vAlign');va.set(qn('w:val'),'center');pr.append(va);pa=c.paragraphs[0];pa.paragraph_format.space_after=Pt(0);pa.paragraph_format.line_spacing=1.05
   if i>0 and len(str(v))<25:pa.alignment=1
   r=pa.add_run(str(v));r.font.size=Pt(10)
   if n==0:r.bold=True;r.font.color.rgb=RGBColor(255,255,255)
 doc.add_paragraph().paragraph_format.space_after=Pt(0)

doc.core_properties.title='Báo cáo metadata tài chính và tối ưu mô hình H3';doc.core_properties.author='Codex hỗ trợ kỹ thuật dự án'
doc.add_paragraph('Báo cáo metadata tài chính và tối ưu mô hình H3','Title')
p('Đợt tiếp theo ngày 23 tháng 10 năm 2026\nDành cho chủ dự án hệ thống phân tích tài chính')
p('Đã xử lý liên kết bằng chứng tài chính, sửa lỗi thời gian trong bước chọn mô hình và train lại 9 tác vụ thị trường. H3 đạt BA 53,70% ở hồi cứu, so với 49,47% của bản trước và 49,58% của baseline. Tuy vậy, H3 vẫn chưa vượt cổng development; các thuật toán mới chưa tạo thêm cải thiện cho H3.')
p('Bảng so sánh tách ba bước để tránh đánh đồng sửa lỗi với tiến bộ thuật toán. “Sửa thời gian” dùng recipe cũ sau khi sửa ranh giới chọn mô hình; “Tối ưu mới” bổ sung cấu hình trên đúng cùng dữ liệu. Các cột hồi cứu dùng cùng mẫu và nhãn cho từng tác vụ.')
names={'direction_h1':'Hướng H1','direction_h2':'Hướng H2','direction_h3':'Hướng H3','direction_h4':'Hướng H4','return_h1':'Return','quantile_h1':'Quantile','volatility_h1':'Volatility','drawdown_h1':'Drawdown','ensemble_h1':'Tổng hợp'}
rows=[]
for x in comparison:
 q=x['model']=='quantile_h1';scale=1 if q else 100;unit='Pinball' if q else 'BA %' if x['metric']=='balanced_accuracy' else 'MAE điểm %'
 rows.append([names[x['model']],*[num(x[k]*scale,4 if q else 2) for k in ['old_challenger','corrected_control','optimized_challenger','baseline']],unit])
table(['Tác vụ','Cũ','Sửa thời gian','Tối ưu mới','Baseline','Đơn vị'],rows,[3.2,2.5,3.1,2.7,2.7,3.6])
p('BA càng cao càng tốt; MAE và pinball càng thấp càng tốt. Mỗi hàng có 7 quý hồi cứu, 160–167 mẫu tùy tác vụ. Baseline cũng được chọn lại theo ranh giới đúng, nên không phải mọi baseline đều giống bản trước. Toàn bộ lịch sử đã được xem trong các lần nghiên cứu, chưa phải tập kiểm chứng tương lai mới.')
p('H1 có cải thiện hồi cứu; H2 và drawdown mới giảm chất lượng. Không tự thay gói đang sử dụng bằng ứng viên chưa đạt. Gói mới gồm 28 PKL nghiên cứu; hai model LNST FPT H1/H2 của đợt trước không bị thay đổi.')

page('1 Metadata đã đối chiếu và điểm còn thiếu')
p('Đã kết nối DB và đối chiếu 37 quý LNST FPT giữa canonical với các fact có bằng chứng PDF công bố. Có 29 quý bằng nhau chính xác và 8 quý lệch. Chênh lệch dù chỉ 1 đồng cũng được giữ lại để truy vết; chưa kết luận tự động rằng nguyên nhân là báo cáo điều chỉnh, OCR hay lỗi provider.')
records=list(csv.DictReader((root/'metadata-after/09_reconciliation.csv').open(encoding='utf-8')));diff=[]
for r in records:
 a,b=Decimal(r['canonical_value']),Decimal(r['document_value'])
 if a!=b:diff.append([r['end_date'],f'{a-b:+,.0f}'.replace(',',' '),r['source_page']])
table(['Ngày kết thúc quý','Canonical trừ PDF bằng đồng','Trang nguồn'],diff,[4.6,9.0,4.2])
h('Đã ghi gì vào database')
p('Bảng mới financial_item_disclosure_review lưu 37 liên kết chỉ tiêu với fact nguồn, gồm giá trị tại lúc kiểm tra, SHA và trạng thái khớp hoặc xung đột. View vw_financial_item_disclosure_review cho tra ngày công bố, phạm vi hợp nhất, đơn vị, loại kỳ, raw ID, URL và trang. Nếu dữ liệu gốc đổi sau đó, cờ stale_review cho biết cần rà lại.')
p('Các liên kết vẫn có training_eligible=false. Giá trị bằng nhau không đủ chứng minh số vendor hiện tại là bản phát hành đầu tiên. Không gắn ngày công bố của PDF vào toàn bộ báo cáo canonical, không sửa số tài chính hoặc scope theo suy đoán. Fingerprint toàn bộ giá trị và metadata canonical trước và sau vẫn giống nhau.')
h('Vì sao chưa thể điền hết metadata')
p('80 raw payload có hợp đồng financial.metadata.v1 nhưng đều chưa có ngày công bố và scope xác minh; 52 payload còn loại kỳ UNKNOWN, 28 chỉ biết POINT_IN_TIME. Toàn bộ 279.374 item canonical vẫn chưa đạt strict PIT. Có trường metadata trong JSON không có nghĩa provider đã cung cấp bằng chứng.')

page('2 Lỗi thời gian đã sửa trong bước chọn mô hình')
p('Trước đây, mỗi lần fit đã loại nhãn tương lai nhưng bước chọn thuật toán và tham số vẫn nhìn toàn bộ development đến cuối 2024. Với H3, dự đoán hồi cứu đầu tiên bắt đầu tại 30/06/2024 và hướng tới 2025Q1. Vì vậy dùng nhãn của 2024Q2, Q3, Q4 để chọn model cho thời điểm đó là không đúng ranh giới thông tin.')
table(['Thành phần H3','Trước sửa','Sau sửa'],[
 ['Origin hồi cứu đầu','30/06/2024','30/06/2024'],
 ['Nhãn cuối được dùng chọn model','Đến 31/12/2024','Đến 31/03/2024'],
 ['Số quý development','20','17'],
 ['Cách so baseline','Có cùng lỗi ranh giới','Cả baseline và ứng viên đều bị giới hạn đúng thời điểm'],
 ['Ngưỡng đạt','Gain BA ít nhất 2 điểm','Giữ nguyên ngưỡng và yêu cầu cân bằng']
],[5.2,6.3,6.3])
p('Quy tắc mới yêu cầu ngày kết thúc mọi nhãn dùng để chọn recipe phải sớm hơn origin hồi cứu đầu tiên. Mốc này tính riêng cho từng horizon: H1, H2, H3, H4 còn lần lượt 19, 18, 17, 16 quý development. Lịch sử dự đoán cũ được giữ nguyên; nhận định cổng của bản cũ phải đọc cùng bản sửa này, đặc biệt H4.')
h('H3 đã cân bằng hơn nhưng chưa đủ mạnh')
table(['Chỉ số hồi cứu','Bản trước','Bản sửa và tối ưu'],[
 ['BA','49,47%','53,70%'],
 ['Nhận đúng không tăng','75,41%','62,30%'],
 ['Nhận đúng tăng','23,53%','45,10%'],
 ['Accuracy trên toàn bộ dòng','Không dùng làm cổng','51,53%'],
 ['Baseline BA trên cùng tập','49,58%','49,58%']
],[7.0,5.4,5.4])
p('Ở development, H3 chỉ đạt BA 52,66%, thấp hơn baseline 54,63%, nên selected vẫn là baseline. Hồi cứu nhận đúng tăng vẫn dưới 50%. Khoảng bootstrap theo quý của chênh BA với baseline còn chứa 0, khoảng từ −3,63 đến +12,32 điểm; chưa đủ bằng chứng về lợi thế ổn định.')

page('3 Những cách tối ưu đã thử')
p('Dữ liệu được xuất mới từ DB lúc 00:45:42 ngày 23/10/2026. Nhánh tối ưu dùng lại chính snapshot đó của nhánh đối chứng để so công bằng, không đọc DB lần hai rồi trộn dữ liệu. Các dataset và nhãn của control và candidate đã được kiểm SHA và đối chiếu từng mẫu.')
table(['Thử nghiệm','Mục đích'],[
 ['Logistic với 5 biến chọn lọc','Giảm số chiều và dao động trên tập nhỏ'],
 ['Logistic với 12 biến và cửa sổ 12 hoặc 20 quý','Kiểm xem lịch sử ngắn hơn có phù hợp trạng thái hiện tại'],
 ['RBF SVM','Thử quan hệ phi tuyến giữa cổ phiếu và trạng thái thị trường'],
 ['ExtraTrees nhỏ','Thử tương tác biến với độ sâu giới hạn để giảm học quá sát'],
 ['Trọng số bằng nhau theo quý','Tránh quý có nhiều mã được đếm mạnh hơn chỉ vì có thêm dòng'],
 ['Ridge và hồi quy phân vị chọn ít biến','Thử giảm phương sai cho lợi suất, khoảng dự báo và rủi ro']
],[7.1,10.7])
p('Các biến dùng thử gồm lợi suất, độ biến động, vị trí so đường trung bình, breadth thị trường, beta, correlation, drawdown và shock đã được Java tính từ dữ liệu quá khứ. Không dùng ticker làm nhãn nhận diện, không thêm dữ liệu tài chính thiếu ngày công bố. 37 quý của riêng FPT chưa đủ để bổ sung feature tài chính đồng đều cho 25 mã.')
h('Kết quả lựa chọn')
p('Đã thêm 5 cấu hình direction dùng chung cho H1–H4; thêm 2 cấu hình cho từng nhóm return, quantile, volatility và drawdown. Kế hoạch được ghi trước fit, không mở vòng tham số mới sau khi xem hồi cứu. H3 vẫn chọn Logistic cũ trong nhánh challenger, nên không có cải thiện bổ sung từ các cấu hình mới.')
p('H4 mới hơn baseline 2,11 điểm BA ở development nhưng chỉ thắng 5/16 quý, chưa đạt điều kiện ít nhất một nửa. H1 mới tốt hơn ở hồi cứu nhưng development kém baseline. Drawdown mới giảm loss development 4,77% nhưng MAE hồi cứu tăng lên 5,96 điểm %, kém baseline 5,88 và bản trước 5,70. Đây là lý do không tự triển khai chỉ dựa vào cổng development.')

page('4 Những phần đã nghiệm thu')
table(['Phần kiểm tra','Kết quả thực tế'],[
 ['Java metadata và contract','17 unit tests PASS; adapter dùng fixture trong unit test'],
 ['Ranh giới chọn mô hình','3 regression tests PASS, gồm sửa nhãn chưa khả dụng'],
 ['Mỗi nhánh control và candidate','125 kiểm tra PASS; 648 output PKL offline và 99 input lỗi bị chặn'],
 ['HTTP model thật của mỗi nhánh','72 requests trả 648 output; không provider mock'],
 ['Đặc trưng và nhãn','Đối chiếu công thức độc lập; thay dữ liệu tương lai không đổi đặc trưng quá khứ'],
 ['Metadata trong schema cô lập','Idempotency và 2 negative constraints PASS; rollback schema'],
 ['Metadata DB chính','37 link; 29 match, 8 conflict, 0 stale, 0 tự nâng điều kiện train'],
 ['So sánh phiên bản','9 tác vụ khớp cohort và nhãn; control và candidate khớp dataset SHA']
],[6.7,11.1])
h('Thay đổi trong pipeline nhận dữ liệu')
p('Parser FinancialStatementMetadata nay ghi danh sách missing_training_metadata và mức metadata_completeness trong provenance. Ngày công bố, scope, loại kỳ, đơn vị và bằng chứng bản gốc thiếu được nêu cụ thể. Cờ automatically_certifies_historical_vintage luôn false; đủ trường vẫn phải kiểm nguồn và phiên bản.')
p('Thay đổi parser được kiểm bằng unit test; chưa chạy replay hàng loạt raw cũ hoặc nghiệm thu ingestion provider mới trong đợt này. Dữ liệu công bố FPT được đối chiếu từ raw PDF đã lấy qua pipeline thật ở đợt trước. Không dùng một fetcher ngoài luồng để giả lập việc bổ sung nguồn.')
h('Phạm vi triển khai')
p('Gói mới dùng CLI và HTTP local, log SQLite nghiên cứu. Chưa có nghiệm thu Spring và ghi prediction DB riêng cho gói candidate này. 218 dự đoán của gói trước trong DB được giữ nguyên; không dùng bằng chứng đó để nói gói mới đã được triển khai. Chỉ bảng review metadata mới được ghi vào DB chính trong đợt này.')
p('Selected trong PKL là lựa chọn development, có thể là baseline. Cả những model qua development cũng chưa được production. Các dịch vụ kiểm thử model đã dừng sau nghiệm thu; chưa thay routing LLM hoặc bật học thêm khi có request.')

page('5 Cách dùng kết quả và việc cần tiếp tục')
h('Gói bàn giao')
p('Thư mục model_suite_20261023_v2 trong repo HE_THONG_DATA_TAI_CHINH_AI_LOCAL chứa runtime_package với 28 PKL, source, evidence của cả hai nhánh, dữ liệu đối chiếu metadata và báo cáo này. Đọc README.md trước khi gọi thử. Các phiên bản cũ được giữ nguyên để truy vết.')
p('Dùng Python 3.13 cùng requirements của gói. Công cụ runtime_package/serve.py nhận root, input JSON, output, ledger và role. Chọn challenger để xem ứng viên nghiên cứu; chọn selected để dùng lựa chọn development. Request phải có UUID, cutoff đã đóng băng và mã có snapshot. Dự đoán không tự cập nhật dữ liệu hay học thêm.')
h('Ưu tiên sửa hệ thống lấy dữ liệu')
p('Trước hết xử lý 8 chênh lệch FPT theo tài liệu và phiên bản cụ thể, có người kiểm tra độc lập các dòng đọc ảnh. Với các mã còn lại, cần lấy từng tài liệu công bố chính thức qua adapter hiện có, giữ ngày giờ công bố, scope, đơn vị, loại kỳ, raw và SHA. Nếu provider không trả ngày thì cần nguồn công bố khác; không thay bằng ngày cuối quý hoặc ngày crawl.')
p('Mỗi bản báo cáo cần tách bản phát hành đầu và bản điều chỉnh. Báo cáo riêng quý không được lẫn với lũy kế; hợp nhất không lẫn riêng lẻ. Metadata phải gắn đúng giá trị và phiên bản tài liệu. Chỉ sau đó mới dựng feature tài chính cho nhiều mã và kiểm lại bằng cùng quy trình theo thời gian.')
h('Bước tiếp theo của H3')
p('Dữ liệu hiện cho thấy các quý có nhiều mã cùng biến động, nên 163 dòng hồi cứu chỉ nằm trong 7 khối thời gian. H3 cũng đổi tỷ lệ dự báo tăng mạnh giữa các giai đoạn: ở một số quý 2025 chỉ dự báo tăng 8–12% mã dù thực tế tăng 48–80%. Điều này gợi ý hạn chế về khả năng thích ứng trạng thái thị trường, chưa chứng minh một biến riêng lẻ là nguyên nhân.')
p('Giữ cấu hình đã khóa và thu dự đoán phát hành thật. Khi có thêm quý mới hoặc dữ liệu tài chính đúng thời điểm đủ rộng, mở experiment mới và so baseline trên tập chưa dùng chọn tham số. Không hạ cổng hoặc tiếp tục thử theo chính các quý hồi cứu đã nhìn để đạt một con số mong muốn.')
p('Kết quả đợt này là sửa được một lỗi đánh giá quan trọng, bổ sung đường truy vết metadata và đo rõ hiệu quả của tối ưu. H3 cân bằng hơn trong hồi cứu nhưng chưa đủ điều kiện thay LLM; các mô hình không cải thiện vẫn được báo cáo đầy đủ.')
footer=sec.footer.paragraphs[0];footer.alignment=2;r=footer.add_run('Local AI v2  |  23 10 2026  |  Trang ');r.font.name='Arial';r.font.size=Pt(9);field=OxmlElement('w:fldSimple');field.set(qn('w:instr'),'PAGE');footer._p.append(field)
path=out/'Bao_cao_metadata_va_toi_uu_H3_20261023_v2.docx';doc.save(path);print(path)
