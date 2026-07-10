package com.pixovid.backend.template;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateBlockRepository extends JpaRepository<TemplateBlock, String> {

  List<TemplateBlock> findByTemplateIdOrderByOrderAsc(String templateId);

  List<TemplateBlock> findByLinkGroupId(String linkGroupId);

  List<TemplateBlock> findByLinkGroupIdAndIdNot(String linkGroupId, String id);

  Optional<TemplateBlock> findByIdAndTemplateId(String id, String templateId);

  long countByTemplateId(String templateId);
}
