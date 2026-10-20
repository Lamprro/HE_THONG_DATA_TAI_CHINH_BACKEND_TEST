package com.hethongdata.taichinh.service.validation;

import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import java.util.UUID;

/** Select a reviewed batch; the native writer still performs its locked, atomic validation. */
public final class SelectedDataVersion {
  private SelectedDataVersion() {}
  public static DataVersionEntity active(DataVersionJpaRepository versions,UUID id,String domain) {
    var v=versions.findById(id).orElseThrow(()->new IllegalArgumentException("Data version not found"));
    if(!domain.equals(v.getDataDomain()) || !"ACTIVE".equals(v.getStatus()))
      throw new IllegalArgumentException("Requires an ACTIVE "+domain+" data version");
    return v;
  }
}
