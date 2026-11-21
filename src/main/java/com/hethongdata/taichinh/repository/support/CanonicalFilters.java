package com.hethongdata.taichinh.repository.support;

public final class CanonicalFilters {
  private CanonicalFilters() {}

  public static final String DAILY_CANONICAL = "is_canonical = true and interval_code = '1d'";
  public static final String ACTIVE_VERSION = "status = 'ACTIVATED'";
}
