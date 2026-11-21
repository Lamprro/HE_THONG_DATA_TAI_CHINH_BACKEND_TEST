package com.hethongdata.taichinh.repository.support;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class VnClock {
  public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private VnClock() {}

  public static LocalDate today() {
    return LocalDate.now(ZONE);
  }

  public static ZonedDateTime now() {
    return ZonedDateTime.now(ZONE);
  }
}
