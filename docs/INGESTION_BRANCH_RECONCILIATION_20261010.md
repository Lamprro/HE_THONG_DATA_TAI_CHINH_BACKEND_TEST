# Ghi nhận nhánh ingestion cũ — 10/10/2026

Nhánh feature/phase1-person1-ingestion có ba commit không phải ancestor của master:
18f909e, f791b7c và b84db10. Đối chiếu git cherry trước merge xác nhận cả ba patch
đã có trên nhánh tích hợp/master dưới commit khác (đều dấu `-`, không có dấu `+`).

Merge dùng chiến lược ours chỉ để nối lịch sử đã được áp dụng. Không đưa lại code
cũ và không ghi đè dispatcher, chẩn đoán upstream hoặc persistence hiện hành.
PR này bổ sung biên bản truy vết; bản nguồn tổng hợp đã pass 172 unit/contract và
72 PostgreSQL integration schema cô lập. Không chạy lại ingestion/DB nghiệp vụ.
