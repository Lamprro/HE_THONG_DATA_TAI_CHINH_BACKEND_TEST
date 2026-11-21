package com.hethongdata.taichinh.repository.support;

public final class VnSql {
  private VnSql() {}

  public static String tradingDate(String column) {
    return "(" + column + " AT TIME ZONE 'Asia/Ho_Chi_Minh')::date";
  }
}
