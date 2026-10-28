package com.hethongdata.taichinh.service.user;
import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
class UserReadSupportTests {
  @Test void rangesUseVietnamMidnightAndRejectUnboundedInputs() {
    var range=UserReadSupport.window(LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-02"),1,15);
    assertThat(range.start().toString()).isEqualTo("2026-09-30T17:00:00Z");assertThat(range.until().toString()).isEqualTo("2026-10-02T17:00:00Z");
    assertThatThrownBy(()->UserReadSupport.window(LocalDate.parse("2000-01-01"),LocalDate.parse("2026-01-01"),1,15)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->UserReadSupport.paging(Integer.MAX_VALUE,500,500)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->UserReadSupport.code("FPT' OR 1=1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->UserReadSupport.option("bad",Set.of("FY","Q1"))).isInstanceOf(IllegalArgumentException.class);
  }
}
