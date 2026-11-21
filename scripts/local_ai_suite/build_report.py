"""Human-readable Word report from saved evidence only. Uses the bundled document runtime."""
import csv,json
from pathlib import Path
from docx import Document
from docx.shared import Inches,Pt,RGBColor,Cm
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
import sys

root=Path(sys.argv[1]).resolve();out=root/'reports';summaries={x['model_id']:x for x in json.loads((root/'suite_summary.json').read_text())};verification=json.loads((root/'verification/receipt.json').read_text());data=json.loads((root/'data_readiness.json').read_text());decisions={x['model']:x for x in json.loads((out/'release_decisions.json').read_text())};rows=list(csv.DictReader((out/'predictions_by_symbol.csv').open(encoding='utf-8-sig')))
doc=Document();sec=doc.sections[0];sec.page_width=Inches(8.5);sec.page_height=Inches(11);sec.top_margin=sec.bottom_margin=Inches(.65);sec.left_margin=sec.right_margin=Inches(.7)
for n,size in [('Normal',11),('Title',22),('Heading 1',17),('Heading 2',12)]:
 st=doc.styles[n];st.font.name='Arial';st.font.size=Pt(size);st.font.color.rgb=RGBColor(0,0,0);st.paragraph_format.space_after=Pt(8);st.paragraph_format.line_spacing=1.08
for n in ['Title','Heading 1','Heading 2']:doc.styles[n].font.bold=True
for border in list(doc.styles.element.iter(qn('w:pBdr'))):border.getparent().remove(border)
def p(t):doc.add_paragraph(t)
def h(t):doc.add_paragraph(t,'Heading 2')
def page(t):doc.add_page_break();doc.add_paragraph(t,'Heading 1')
def table(headers,rows,widths):
 t=doc.add_table(rows=1,cols=len(headers));t.autofit=False;bd=OxmlElement('w:tblBorders')
 for edge in ['top','left','bottom','right','insideH','insideV']:
  e=OxmlElement('w:'+edge);e.set(qn('w:val'),'single');e.set(qn('w:sz'),'4');e.set(qn('w:color'),'D9D9D9');bd.append(e)
 t._tbl.tblPr.append(bd)
 for i,w in enumerate(widths):t.columns[i].width=Cm(w)
 for ri,values in enumerate([headers]+rows):
  row=t.rows[0] if ri==0 else t.add_row();row._tr.get_or_add_trPr().append(OxmlElement('w:cantSplit'))
  if ri==0:row._tr.get_or_add_trPr().append(OxmlElement('w:tblHeader'))
  for i,(c,val) in enumerate(zip(row.cells,values)):
   c.width=Cm(widths[i]);pr=c._tc.get_or_add_tcPr();sh=OxmlElement('w:shd');sh.set(qn('w:fill'),'24445E' if ri==0 else ('F3F5F7' if ri%2==0 else 'FFFFFF'));pr.append(sh)
   mar=OxmlElement('w:tcMar')
   for side in ['top','bottom','left','right']:
    e=OxmlElement('w:'+side);e.set(qn('w:w'),'70');e.set(qn('w:type'),'dxa');mar.append(e)
   pr.append(mar);pa=c.paragraphs[0];pa.paragraph_format.space_after=Pt(0);pa.paragraph_format.line_spacing=1.05;run=pa.add_run(str(val));run.font.size=Pt(10)
   if ri==0:run.bold=True;run.font.color.rgb=RGBColor(255,255,255)
 doc.add_paragraph().paragraph_format.space_after=Pt(0)
fmt=lambda x:f'{100*x:.2f}'.replace('.',',')+'%'

def pc(v):return f'{100*float(v):.2f}'.replace('.',',')+'%'
def n(v):return f'{float(v):.5f}'.replace('.',',')
def label(v):return 'Tăng' if v=='UP' else 'Không tăng'
names={'direction_h1':'Hướng giá H1','direction_h2':'Hướng giá H2','direction_h3':'Hướng giá H3','direction_h4':'Hướng giá H4','return_h1':'Sinh lời H1','quantile_h1':'P10 P50 P90 H1','volatility_h1':'Biến động H1','drawdown_h1':'Sụt giảm H1','ensemble_h1':'Tổng hợp H1'}
doc.core_properties.title='Báo cáo hoàn thiện bộ mô hình local AI';doc.core_properties.subject='Kết quả train và gói chạy nghiên cứu ngày 21 tháng 10 năm 2026';doc.core_properties.author='Nhóm phát triển dự án AI tài chính local'
doc.add_paragraph('Báo cáo hoàn thiện bộ mô hình local AI','Title')
p('Dành cho chủ dự án AI tài chính local\nNgày 21 tháng 10 năm 2026 theo giờ Việt Nam')
h('Kết quả chính')
p('Đã train đồng bộ 9 tác vụ, đóng gói 28 file pkl, kiểm tra gọi dự đoán và tạo bộ kết quả cho 24 mã. Bộ train có nguồn chạy lại, snapshot và checksum, cổng so baseline, mô hình tổng hợp theo thời gian, API local và log riêng. Có thể chạy thử gói nghiên cứu đã bàn giao.')
p('Chưa đủ căn cứ đưa cả bộ vào sử dụng chính thức hoặc thay toàn bộ LLM. Drawdown là ứng viên tốt nhất để theo dõi song song. H4 vượt cổng development nhưng khả năng phân biệt tăng và không tăng trên hồi cứu còn gần 50%. Các tác vụ khác vẫn dùng baseline khi ứng viên chưa đạt cổng.')
table(['Nhóm','Đã làm trong đợt này','Trạng thái'],[
 ['Hướng giá H1 đến H4','Train riêng từng khoảng và so cùng baseline','Nghiên cứu'],
 ['Sinh lời H1','Ridge và hồi quy trung vị cùng đối chứng','Giữ median'],
 ['P10 P50 P90','Train khoảng riêng theo rủi ro từng mã','Challenger nghiên cứu'],
 ['Volatility','Train lại theo trạng thái thị trường','Chưa ổn định'],
 ['Drawdown','Giảm lỗi ở development và hồi cứu','Ứng viên chạy song song'],
 ['Tổng hợp H1','Train từ dự báo ngoài mẫu của 4 model con','Chưa thắng baseline'],
 ['5 chỉ tiêu tài chính','Kiểm tra điều kiện và cấu hình dữ liệu cần có','Chưa đủ nguồn để train']
],[3.5,9.0,5.3])
p('9 tác vụ không có nghĩa là 28 mô hình độc lập. Mỗi tác vụ có selected, challenger, baseline; thêm một file chứa model con của bộ tổng hợp. Selected là lựa chọn từ development, có thể chỉ là một quy tắc hoặc trung vị lịch sử.')

page('1 Dữ liệu thực sự được dùng')
p('Đã thử đọc DB ngày 21/10 nhưng gặp SQLSTATE 08001; kiểm tra DNS được, kết nối TCP bị timeout. Lần thử DB cuối cùng cũng thất bại. Vì vậy đợt này chạy lại snapshot thị trường ngày 20/10 lúc 23:36:28 +07, có đối chiếu checksum. Audit tài chính dùng snapshot riêng trước đó cùng ngày. Không gọi dữ liệu cũ là DB đã cập nhật hôm nay.')
table(['Dữ liệu','Số lượng hoặc tình trạng','Cách sử dụng'],[
 ['Giá cổ phiếu','66.794 dòng của 25 mã','Adjusted close cùng provider và basis đã xác minh'],
 ['Chỉ số','6.462 dòng nguồn','Giữ provenance; không coi là 6.462 ngày duy nhất'],
 ['Mẫu mã tại quý','1.014 origin cho nhóm market','Không tương đương 1.014 quý độc lập'],
 ['Mã đủ đầu vào mới nhất','24 mã tại 30/09/2026','HPG bị loại do chuỗi nguồn và coverage'],
 ['Đặc trưng','43 market và trạng thái; 39 risk','Mỗi thuật toán chỉ dùng nhóm đã cấu hình'],
 ['Báo cáo tài chính','279.374 item canonical; 0 strict PIT','Chưa đưa vào model giá hoặc train tài chính']
],[3.6,6.3,7.9])
h('Đã sửa điểm lệch giữa các bộ nguồn train')
p('Bản nghiên cứu trước ưu tiên CafeF riêng cho EIB, HSG, VNM. Pipeline mới biên dịch trực tiếp QuarterlyMarketFeatures từ backend và dùng thứ tự VNDIRECT rồi CafeF của lớp đó. Bộ origin giảm từ 1.016 xuống 1.014; số mẫu hồi cứu của H3 và quantile cũng thay đổi. Đây là thay đổi chính sách nguồn, không phải tự nhiên độ chính xác tăng.')
p('Đã so lại trên giao các mẫu có cùng nhãn: H3 có 160 dòng khớp, BA cả hai phiên bản cùng bằng 49,17%. Bởi vậy không công bố H3 tốt hơn bản trước chỉ vì điểm tổng hợp khác. Việc dùng chung công thức Java chưa chứng nhận query eligibility hay luồng Spring cho các model mới; phần tích hợp đó vẫn cần nghiệm thu riêng.')
h('Các lớp kiểm tra được giữ')
p('Nguồn phải đúng trạng thái, provider và khóa dữ liệu; giá đầu và cuối cùng cơ sở điều chỉnh; các quý có đủ coverage; đường risk phải có đủ phiên và điểm giá trước quý. Dữ liệu sau cutoff không được đi vào feature; nhãn chưa kết thúc không được dùng train. Không bù nến, trộn nguồn hoặc tự tạo ngày công bố để tăng số mẫu.')

page('2 Kết quả dự đoán hướng giá')
p('Bảng là challenger được lựa chọn bằng development rồi đánh giá hồi cứu trên quý đích 2025Q1 đến 2026Q3. Baseline là mốc mạnh nhất trong nhóm đối chứng trên development. BA là trung bình khả năng nhận đúng hai lớp; khác với accuracy tổng thể và khác với xác suất tăng của một mã.')
table(['Model','BA ứng viên','BA baseline','Nhận đúng không tăng','Nhận đúng tăng'],[[names[k],pc(summaries[k]['retrospective']['challenger']['balanced_accuracy']),pc(summaries[k]['retrospective']['baseline']['balanced_accuracy']),pc(summaries[k]['retrospective']['challenger']['recall_not_up']),pc(summaries[k]['retrospective']['challenger']['recall_up'])] for k in ['direction_h1','direction_h2','direction_h3','direction_h4','ensemble_h1']],[3.5,3.1,3.1,4.2,3.9])
h('H1 và H2')
p('H1 ứng viên chỉ nhận được 20,25% trường hợp không tăng, dù nhận 88,64% trường hợp tăng. H2 cân bằng hai chiều hơn nhưng development kém baseline 13,54 điểm BA. Cả hai chưa đạt cổng. Các file selected mới tương ứng dùng momentum 2 quý và momentum 1 quý. Không thay H1 v4.1 đã tích hợp Spring bằng bộ nghiên cứu này.')
h('H3 và H4')
p('H3 nhận không tăng 75,41% nhưng nhận tăng chỉ 23,53%, nên BA còn 49,47%. Trạng thái thị trường chưa tách được tín hiệu riêng của doanh nghiệp ở khoảng 3 quý. Đây là nhận định từ kết quả và cấu trúc dữ liệu, chưa phải bằng chứng nhân quả. Không tiếp tục chỉnh ngưỡng theo chính các nhãn hồi cứu đã nhìn thấy.')
p('H4 tăng 3,19 điểm BA so baseline ở development và qua cổng đó. Hồi cứu hơn baseline nhưng BA chỉ 50,11%, nhận không tăng 38,18%. Baseline H4 ở giai đoạn này chỉ 45,10%; vượt mốc yếu chưa đủ chứng minh dự báo tốt. Khoảng bất định của chênh lệch vẫn chứa 0.')
h('Bộ tổng hợp chưa tạo thêm giá trị')
p('Tổng hợp H1 có BA 40,61%, kém baseline 50,19%. Các model con có lỗi và thông tin trùng nhau, nên việc ghép chúng chưa tạo tín hiệu mới đủ mạnh. Bộ tổng hợp selected phải dùng momentum; file challenger vẫn được giữ để nghiên cứu. Không gắn nhãn sẵn sàng chỉ vì có thêm một file pkl.')

page('3 Sinh lời phân vị và rủi ro')
table(['Tác vụ','Chỉ tiêu hồi cứu','Ứng viên','Baseline','Cổng development'],[
 ['Sinh lời','MAE return','13,45 điểm %','12,66 điểm %','Không đạt'],
 ['P10 P50 P90','Pinball loss','0,04301','0,04345','Không đạt'],
 ['Volatility','MAE biến động năm hóa','9,57 điểm %','8,91 điểm %','Đạt'],
 ['Drawdown','MAE sụt giảm','5,70 điểm %','5,88 điểm %','Đạt']
],[3.1,4.4,3.3,3.3,3.7])
p('MAE là độ lệch tuyệt đối trung bình; nhỏ hơn tốt hơn. Ví dụ dự đoán drawdown 10% nhưng thực tế 16% thì sai 6 điểm phần trăm. Pinball loss là thước đo riêng của phân vị, không phải tỷ lệ dự đoán đúng.')
h('Drawdown là ưu tiên tiếp theo')
p('Hồi quy trung vị dùng rủi ro mã và trạng thái thị trường, trộn 50% với median baseline, giảm loss development 2,51% và thắng 14/20 quý. Hồi cứu giảm MAE từ 5,88 còn 5,70 điểm phần trăm. Loss khi mỗi quý có trọng số bằng nhau giảm khoảng 2,84%.')
p('Khoảng bootstrap theo 7 quý của chênh lệch loss xấp xỉ từ -0,00658 đến +0,00306, vẫn chứa 0. Vì vậy chỉ chọn làm ứng viên theo dõi song song; chưa chứng minh lợi thế chắc chắn hoặc đủ điều kiện production. Drawdown dùng giá đóng cửa điều chỉnh, không đo mức rơi thấp nhất trong ngày.')
h('Volatility và sinh lời')
p('Volatility qua development nhưng hồi cứu lại kém baseline; sai lệch còn thiên về đánh giá thấp rủi ro. Return H1 dùng Ridge alpha 100 làm challenger, vẫn thua median. File selected của return là median lịch sử khoảng 3,57% cho mọi mã tại mốc này; đó là baseline chung, chưa phải model phân biệt tốt từng công ty.')
h('Phân vị riêng theo mã')
p('Challenger trộn 75% phân vị lịch sử và 25% phân vị đã chuẩn hóa theo rủi ro mã. Nó cho 24 khoảng khác nhau và loss development tốt hơn 0,44%, dưới yêu cầu 2%. File selected vẫn là phân vị lịch sử, cùng khoảng cho mọi mã. Muốn xem khoảng điều kiện từng mã phải dùng role challenger và giữ nhãn nghiên cứu.')
p('Coverage P10 đến P90 chưa được hiệu chuẩn thành xác suất 80%. Kết quả hồi cứu có cải thiện nhỏ và khoảng bootstrap thuận lợi nhưng đã được quan sát trong nghiên cứu; không dùng riêng kết quả đó để vượt cổng development đã chốt.')

page('4 Pipeline train và cách chọn mô hình')
table(['Bước','Việc thực hiện và đầu ra'],[
 ['1 Thu thập chuẩn','Dùng ingestion và validation rồi builder hiện có của Spring; không thay bằng fetcher Python tự dựng'],
 ['2 Chốt dữ liệu','Snapshot chỉ đọc, checksums và thời gian; replay phải được chọn rõ'],
 ['3 Tính đầu vào','Java tạo feature và lineage theo cutoff; dựng nhãn đúng horizon'],
 ['4 Train theo thời gian','Mỗi quý chỉ fit bằng nhãn đã kết thúc trước cutoff; clip và scale trong train'],
 ['5 Chọn ở development','2020 đến 2024; lưu selection trước khi chấm hồi cứu'],
 ['6 Đóng gói và kiểm tra','3 role, manifest, pkl; so API và kết quả offline; kiểm hash và cache'],
 ['7 Theo dõi thực tế','Lưu thời điểm phát hành thật; chờ hết quý và đủ dữ liệu rồi chấm']
],[4.0,13.8])
h('Thuật toán và tham số đã sử dụng')
p('Đã thử 115 cấu hình gồm baseline và biến thể ngưỡng trong 9 tác vụ. Logistic Regression thử C 0,03/0,3 cùng cửa sổ 20 quý; có mốc LR15 C 0,1. Return thử Ridge alpha 10/100, hồi quy trung vị alpha 0,001/0,01 và boosting nhỏ. Risk thử shrinkage 0,25/0,5, kernel và median theo trạng thái; tham số đầy đủ nằm trong manifest và plan.')
p('Cổng hướng giá cần tăng ít nhất 2 điểm BA, cả hai recall ít nhất 50%, thắng ít nhất nửa số quý. Task dùng loss cần giảm ít nhất 2% và thắng ít nhất nửa số quý. Quantile thêm điều kiện coverage và khoảng không hằng theo mã. Những quy tắc này được giữ trước khi chấm hồi cứu; không hạ cổng để chọn một model vừa train xong.')
h('Train bộ tổng hợp đúng thứ tự')
p('Bốn model con cố định dự báo hướng H1, return H1, volatility và drawdown tại từng origin. Chúng chỉ được fit bằng dữ liệu cũ hơn origin đó. Bảng dự báo ngoài mẫu mới là đầu vào train Logistic Regression tổng hợp. Meta cũng chỉ học các nhãn đã trưởng thành, và có outer evaluation theo thời gian. Không dùng dự đoán của model fit toàn lịch sử làm dữ liệu train meta trên chính các dòng đó.')
p('Các giai đoạn lịch sử đều đã được xem qua nhiều lần trong dự án. Kiểm định lồng nhau giảm một số sai lệch trong quy trình hiện tại nhưng không biến lịch sử này thành test tương lai chưa từng thấy.')

page('5 Dùng gói pkl và đọc kết quả')
p('Gói bàn giao nằm trong HE_THONG_DATA_TAI_CHINH_AI_LOCAL/model_suite_20261021. Thư mục runtime_package chứa API local, model và manifest; models chứa dữ liệu nghiên cứu và đánh giá từng tác vụ; reports chứa Word, bảng so sánh và kết quả theo mã; tools chứa nguồn chạy lại pipeline.')
h('Chạy thử trên máy hiện có')
p('Chạy RUN_DEMO.ps1 từ thư mục gói để dự đoán FPT bằng Python environment hiện có; có thể truyền danh sách mã khác. Script tạo kết quả và log local riêng. Với API, chạy serve.py, đặt Bearer token riêng trong environment và chỉ bind 127.0.0.1. README có lệnh đầy đủ. Đây là runtime Python nghiên cứu; chưa phải endpoint Spring mới đã persist vào DB nghiệp vụ.')
p('Request chọn mã và cutoff 30/09/2026. Runtime dùng feature đã đóng băng, không tự đọc DB và không học thêm khi predict. Muốn mốc mới phải tạo snapshot và version mới, train rồi kiểm định lại. Các request cùng request_id và cùng input dùng cache; đổi input trên cùng ID bị từ chối.')
table(['Mã','H1 Q4 2026','H2 Q1 2027','H3 Q2 2027','H4 Q3 2027'],[[r['ticker']]+[label(r['direction_h'+str(i)]) for i in range(1,5)] for r in rows if r['ticker'] in ['ACB','EIB','FPT','HSG','VNM']],[2.0,3.95,3.95,3.95,3.95])
p('Bảng dùng role selected. Tất cả hướng so với cùng giá tại origin 30/09, không so với quý tương lai liền trước. H1 đến H3 ở gói này là baseline momentum; H4 là challenger qua development. Score 0 hoặc 1 của momentum chỉ biểu diễn hướng quy tắc, không phải độ tin cậy 0% hoặc 100%.')
p('Ví dụ FPT có volatility selected khoảng 27,52% năm hóa và drawdown khoảng 11,72%. Chúng mô tả rủi ro theo định nghĩa model, không phải FPT sẽ giảm đúng 11,72% hoặc tăng 27,52%. Return median và khoảng phân vị selected là baseline chung, nên chưa thể coi chúng là dự báo giá riêng đáng tin cậy cho FPT.')
p('Kết quả được phát hành thực ngày 21/10 khi Q4 đã bắt đầu, từ dữ liệu chốt 30/09. Đây là FROZEN_SNAPSHOT_RESEARCH và PARTIAL_HORIZON_RESEARCH, không phải dự báo đã phát hành trước toàn Q4. CSV predictions_by_symbol.csv có đủ 24 mã; conditional_quantiles_by_symbol.csv tách rõ challenger và baseline.')

page('6 Điều kiện để train phần thay LLM')
p('Các model giá và rủi ro chưa thay trực tiếp phần Financial LLM. Luồng hiện có cần dự báo lợi nhuận sau thuế, lợi nhuận trước thuế, tài sản, vốn chủ sở hữu, nợ phải trả và kịch bản có bằng chứng. Chưa tạo model tài chính vì snapshot không có item đủ điều kiện point in time nghiêm ngặt.')
table(['Cần bổ sung hoặc xác minh','Vì sao ảnh hưởng trực tiếp đến train'],[
 ['Ngày công bố thật và available_at','Biết báo cáo nào thị trường đã có tại từng cutoff; ngày cuối quý không thay được'],
 ['Scope hợp nhất hoặc riêng lẻ','Không ghép hai quy mô công ty thành một chuỗi'],
 ['STANDALONE_QUARTER hoặc YTD','Lợi nhuận riêng quý khác lợi nhuận lũy kế; phép trừ phải cùng vintage'],
 ['Currency và unit_scale','Không học nhầm VND với tỷ đồng; kiểm tính nhất quán tài sản và nguồn vốn'],
 ['First release và revision','Không dùng báo cáo sửa sau như thể đã biết trong quá khứ'],
 ['Item mapping và evidence','Đúng target, đúng loại báo cáo và truy được dữ liệu gốc']
],[6.2,11.6])
p('Audit ngày 20/10 có 279.374 item thiếu publication và scope, 98.353 item chưa rõ period basis, 142 item lỗi hoặc thiếu đơn vị, 279.232 item chưa có contract metadata đủ bằng chứng. Có nhiều chỉ số sạch về giá trị số vẫn chưa có nghĩa đủ dữ liệu train lịch sử đúng thời điểm.')
h('Thứ tự xử lý tiếp khi nguồn sẵn sàng')
p('Khôi phục kết nối DB, thu thập metadata qua adapter Spring đã có, kiểm định raw và build lại có provenance. Đối chiếu coverage theo từng target và ngày công bố. Train lợi nhuận sau thuế trước với seasonal naive, Ridge hoặc hồi quy trung vị; sau đó lợi nhuận trước thuế và nhóm bảng cân đối. Kịch bản tài chính cần sai số ngoài mẫu của các model đó; Java tiếp tục tính projected value và kiểm tra ràng buộc.')
p('NEWS cần tập nhãn người duyệt và nội dung thực; macro cần ngày phát hành và vintage. Không dùng dự báo LLM làm số liệu thực tế, không tự đặt ngày công bố hay scope. Data_requirements.json đã ghi các trường và quy tắc cần có. Những model này còn chờ dữ liệu; báo cáo không gắn nhãn hoàn tất cho chúng.')

page('7 Kiểm chứng và quyết định bàn giao')
table(['Kiểm tra thực sự chạy','Kết quả'],[
 ['Kiểm tra bộ model và runtime',str(len(verification['checks']))+' kiểm tra PASS; 99 input sai bị từ chối'],
 ['Nạp pkl so kết quả train','648 dự đoán khớp ở 9 model và 3 role'],
 ['HTTP gọi pickle thật','72 request thành công; 648 đầu ra model; không dùng provider mock'],
 ['Công thức độc lập','5 công thức trạng thái trên 1.014 origin; sai số lớn nhất 5,21 nhân 10 mũ -16'],
 ['Đổi dữ liệu tương lai','743 origin market và 721 origin risk giữ nguyên feature và hash quá khứ'],
 ['Giả mạo artifact và cache','Hash sai bị chặn; cache idempotent và conflict đúng; log không còn RUNNING'],
 ['Kết quả cho 24 mã','216 đầu ra mới; cả 216 PENDING_TARGET_MATURITY; không tạo actual tương lai']
],[6.5,11.3])
p('Các process API thử đã dừng. Không ghi bản ghi kiểm thử vào DB nghiệp vụ. Không chạy lại Maven hoặc nghiệm thu Spring cho những target mới trong đợt này; bằng chứng Spring H1 ngày 17/10 thuộc phạm vi khác. Không bật scheduler hoặc tự đổi model đang dùng.')
h('Bước nghiệm thu tương lai đã được chốt')
p('Mốc đầu tiên cho một dự báo trước toàn quý mới là origin 31/12/2026, target H1 Q1/2027, với cấu hình đóng băng trước khi phát hành. Lưu thời điểm gọi thật cùng hash, chờ quý kết thúc, kiểm tra đường giá rồi mới chấm. Tách các dự báo phát hành muộn và báo cáo tỷ lệ mã bị loại. Kế hoạch đặt ít nhất 8 quý mục tiêu để xem xét lại, không tự động chứng nhận production khi đủ số quý.')
p('Evaluate_forward.py đã chạy trên batch mới và trả đúng 216 trạng thái chờ. Công cụ kiểm snapshot, identity package/model, ngày phát hành, target và provider; kết quả hiện lưu local. Nếu origin bị sửa về sau thì đánh dấu; không trộn nó vào một điểm chính xác được chứng nhận.')
h('Phần đã sẵn sàng và phần còn tồn đọng')
p('Đã sẵn sàng chạy nghiên cứu: pipeline train, 9 task, gói pkl, API loopback, log local, báo cáo lỗi theo mã và quý, so baseline và công cụ chờ actual. Drawdown được chọn để ưu tiên shadow evaluation. Còn tồn đọng: kết nối DB hiện tại, metadata tài chính và vintage, kiểm định forward chưa biết trước, tích hợp hợp đồng và persistence Spring cho các task mới. Vì các phần đó chưa hoàn thành, production_eligible và replace_llm đều giữ false.')
p('Bằng chứng đọc kèm: suite_plan_before_training.json, replay_provenance.json, verification/receipt.json, reports/model_comparison.csv, errors_by_quarter_and_ticker.csv, release_decisions.json và forward_evaluation_now/receipt.json. Các file được giữ cùng thời gian và checksum để lần tiếp theo không phải suy trạng thái từ lời mô tả.')

for t in doc.tables:
 for row in t.rows:
  for cell in row.cells:
   v=OxmlElement('w:vAlign');v.set(qn('w:val'),'center');cell._tc.get_or_add_tcPr().append(v)
footer=doc.sections[0].footer.paragraphs[0];footer.alignment=2;run=footer.add_run('Local AI  |  21 10 2026  |  Trang ');run.font.name='Arial';run.font.size=Pt(9);field=OxmlElement('w:fldSimple');field.set(qn('w:instr'),'PAGE');footer._p.append(field)
path=out/'Bao_cao_hoan_thien_bo_mo_hinh_local_AI_20261021.docx';doc.save(path);print(path)
