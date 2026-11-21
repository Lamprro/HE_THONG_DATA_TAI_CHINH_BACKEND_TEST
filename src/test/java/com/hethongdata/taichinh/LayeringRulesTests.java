package com.hethongdata.taichinh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LayeringRulesTests {
  // Allowlist: file còn được phép có SQL — xóa dần sau mỗi đợt, cuối cùng phải rỗng
  static final Set<String> ALLOW = Set.of(
    "service/localai/LocalAiTrainerService.java",
    "service/user/UserFinancialService.java",
    "service/user/UserMarketService.java",
    "service/localai/LocalAiSuiteService.java",
    "service/user/UserLocalAiService.java",
    "service/user/UserSecurityService.java",
    "service/user/UserNewsService.java",
    "service/user/UserReadSupport.java"
  );

  @Test
  void noSqlOutsideRepository() throws Exception {
    Path root = Path.of("src/main/java/com/hethongdata/taichinh");
    var bad = new ArrayList<String>();
    try (var files = Files.walk(root)) {
      for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        String rel = root.relativize(p).toString().replace('\\', '/');
        if (rel.startsWith("repository/") || ALLOW.contains(rel)) continue;
        String s = Files.readString(p);
        if (s.contains("JdbcTemplate") || s.matches("(?is).*\\b(select\\s+[\\w*(]|insert\\s+into|update\\s+\\w+\\s+set|delete\\s+from)\\b.*")) {
          bad.add(rel);
        }
      }
    }
    assertEquals(List.of(), bad, "SQL/JdbcTemplate ngoài repository");
  }
}
