package com.hethongdata.taichinh.service.master;

import com.hethongdata.taichinh.entity.master.CompanyAliasEntity;
import com.hethongdata.taichinh.entity.master.CompanyEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.repository.master.MasterDataRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Explicit, repeatable starter universe. Values are stable company/security master attributes only.
 * Volatile share counts and unverified ISIN values are intentionally left null for raw ingestion to
 * collect later.
 */
@Service
public class MasterDataCatalogService {
    private static final BigDecimal COMMON_SHARE_PAR_VALUE = new BigDecimal("10000");
    private final MasterDataRepository masterData;
    private final SecurityJobProvisioningService jobProvisioning;

    public MasterDataCatalogService(
            MasterDataRepository masterData, SecurityJobProvisioningService jobProvisioning) {
        this.masterData = masterData;
        this.jobProvisioning = jobProvisioning;
    }

    @Transactional
    public int seed() {
        definitions().forEach(this::upsert);
        return definitions().size();
    }

    private void upsert(Definition value) {
        CompanyEntity company =
                masterData
                        .findCompanyByCode(value.symbol())
                        .orElseGet(
                                () ->
                                        masterData.saveCompany(
                                                CompanyEntity.create(
                                                        value.taxCode(),
                                                        value.symbol(),
                                                        value.legalName(),
                                                        value.shortName(),
                                                        value.englishName(),
                                                        value.industryCode(),
                                                        value.industryName(),
                                                        value.sectorName(),
                                                        value.website(),
                                                        value.headquarters(),
                                                        value.foundedDate(),
                                                        "LISTED",
                                                        value.description(),
                                                        true)));
        for (String alias : value.aliases()) {
            if (!masterData.aliasExists(company.getId(), alias)) {
                masterData.saveAlias(
                        CompanyAliasEntity.create(
                                company.getId(),
                                alias,
                                alias.equals(value.englishName()) ? "ENGLISH_NAME" : "SHORT_NAME"));
            }
        }
        SecurityEntity security =
                masterData
                        .findSecurityBySymbol(value.symbol())
                        .orElseGet(
                                () ->
                                        masterData.saveSecurity(
                                                SecurityEntity.create(
                                                        company.getId(),
                                                        value.symbol(),
                                                        "HOSE",
                                                        "STOCK",
                                                        null,
                                                        "VND",
                                                        value.listedDate(),
                                                        null,
                                                        null,
                                                        COMMON_SHARE_PAR_VALUE,
                                                        true,
                                                        true)));
        jobProvisioning.provision(security);
    }

    private List<Definition> definitions() {
        return List.of(
                d(
                        "FPT",
                        "0101248141",
                        "Công ty Cổ phần FPT",
                        "FPT",
                        "FPT Corporation",
                        "TECH",
                        "Công nghệ thông tin",
                        "Công nghệ",
                        "https://fpt.com.vn",
                        "10 Phạm Văn Bạch, Cầu Giấy, Hà Nội",
                        "1988-09-13",
                        "2006-12-13",
                        "Tập đoàn công nghệ, viễn thông và giáo dục."),
                d(
                        "VNM",
                        "0300588569",
                        "Công ty Cổ phần Sữa Việt Nam",
                        "Vinamilk",
                        "Vietnam Dairy Products Joint Stock Company",
                        "FOOD",
                        "Thực phẩm và đồ uống",
                        "Hàng tiêu dùng",
                        "https://www.vinamilk.com.vn",
                        "10 Tân Trào, Quận 7, TP. Hồ Chí Minh",
                        "1976-08-20",
                        "2006-01-19",
                        "Doanh nghiệp sản xuất sữa và sản phẩm dinh dưỡng."),
                d(
                        "HPG",
                        "0900189284",
                        "Công ty Cổ phần Tập đoàn Hòa Phát",
                        "Hòa Phát",
                        "Hoa Phat Group Joint Stock Company",
                        "MATERIALS",
                        "Sắt thép",
                        "Vật liệu cơ bản",
                        "https://www.hoaphat.com.vn",
                        "Khu công nghiệp Phố Nối A, Hưng Yên",
                        "1992-08-26",
                        "2007-11-15",
                        "Tập đoàn công nghiệp với lĩnh vực cốt lõi là thép."),
                d(
                        "VCB",
                        "0100112437",
                        "Ngân hàng Thương mại Cổ phần Ngoại thương Việt Nam",
                        "Vietcombank",
                        "Joint Stock Commercial Bank for Foreign Trade of Vietnam",
                        "BANK",
                        "Ngân hàng",
                        "Tài chính",
                        "https://www.vietcombank.com.vn",
                        "198 Trần Quang Khải, Hoàn Kiếm, Hà Nội",
                        "1963-04-01",
                        "2009-06-30",
                        "Ngân hàng thương mại cổ phần."),
                d(
                        "VIC",
                        "0101245486",
                        "Tập đoàn Vingroup - Công ty Cổ phần",
                        "Vingroup",
                        "Vingroup Joint Stock Company",
                        "REAL_ESTATE",
                        "Bất động sản",
                        "Bất động sản",
                        "https://www.vingroup.net",
                        "7 Bằng Lăng 1, Vinhomes Riverside, Hà Nội",
                        "1993-08-08",
                        "2007-09-19",
                        "Tập đoàn đa ngành."),
                d(
                        "MSN",
                        "0303576603",
                        "Công ty Cổ phần Tập đoàn Masan",
                        "Masan",
                        "Masan Group Corporation",
                        "CONSUMER",
                        "Hàng tiêu dùng",
                        "Hàng tiêu dùng",
                        "https://www.masangroup.com",
                        "Tầng 10, Central Plaza, 17 Lê Duẩn, TP. Hồ Chí Minh",
                        "1996-04-01",
                        "2009-11-05",
                        "Tập đoàn tiêu dùng - bán lẻ."),
                d(
                        "BID",
                        "0100150619",
                        "Ngân hàng Thương mại Cổ phần Đầu tư và Phát triển Việt Nam",
                        "BIDV",
                        "Joint Stock Commercial Bank for Investment and Development of Vietnam",
                        "BANK",
                        "Ngân hàng",
                        "Tài chính",
                        "https://www.bidv.com.vn",
                        "35 Hàng Vôi, Hoàn Kiếm, Hà Nội",
                        "1957-04-26",
                        "2014-01-24",
                        "Ngân hàng thương mại cổ phần."),
                d(
                        "TCB",
                        "0100230800",
                        "Ngân hàng Thương mại Cổ phần Kỹ thương Việt Nam",
                        "Techcombank",
                        "Vietnam Technological and Commercial Joint Stock Bank",
                        "BANK",
                        "Ngân hàng",
                        "Tài chính",
                        "https://www.techcombank.com",
                        "6 Quang Trung, Hoàn Kiếm, Hà Nội",
                        "1993-09-27",
                        "2018-06-04",
                        "Ngân hàng thương mại cổ phần."),
                d(
                        "MBB",
                        "0100283873",
                        "Ngân hàng Thương mại Cổ phần Quân đội",
                        "MB",
                        "Military Commercial Joint Stock Bank",
                        "BANK",
                        "Ngân hàng",
                        "Tài chính",
                        "https://www.mbbank.com.vn",
                        "18 Lê Văn Lương, Cầu Giấy, Hà Nội",
                        "1994-11-04",
                        "2011-11-01",
                        "Ngân hàng thương mại cổ phần."),
                d(
                        "ACB",
                        "0301452948",
                        "Ngân hàng Thương mại Cổ phần Á Châu",
                        "ACB",
                        "Asia Commercial Joint Stock Bank",
                        "BANK",
                        "Ngân hàng",
                        "Tài chính",
                        "https://www.acb.com.vn",
                        "442 Nguyễn Thị Minh Khai, Quận 3, TP. Hồ Chí Minh",
                        "1993-06-04",
                        "2020-12-09",
                        "Ngân hàng thương mại cổ phần."));
    }

    private static Definition d(
            String symbol,
            String taxCode,
            String legalName,
            String shortName,
            String englishName,
            String industryCode,
            String industryName,
            String sectorName,
            String website,
            String headquarters,
            String foundedDate,
            String listedDate,
            String description) {
        return new Definition(
                symbol,
                taxCode,
                legalName,
                shortName,
                englishName,
                industryCode,
                industryName,
                sectorName,
                website,
                headquarters,
                LocalDate.parse(foundedDate),
                LocalDate.parse(listedDate),
                description,
                List.of(shortName, englishName));
    }

    private record Definition(
            String symbol,
            String taxCode,
            String legalName,
            String shortName,
            String englishName,
            String industryCode,
            String industryName,
            String sectorName,
            String website,
            String headquarters,
            LocalDate foundedDate,
            LocalDate listedDate,
            String description,
            List<String> aliases) {}
}
