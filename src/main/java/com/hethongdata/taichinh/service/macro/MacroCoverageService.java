package com.hethongdata.taichinh.service.macro;

import com.hethongdata.taichinh.repository.jdbc.macro.MacroQueryRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class MacroCoverageService {
  private final MacroQueryRepository repository;

  public MacroCoverageService(MacroQueryRepository repository) {
    this.repository = repository;
  }

  public List<Map<String, Object>> coverage() {
    return repository.vietnamCoverage();
  }
}
