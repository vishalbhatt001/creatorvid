package com.pixovid.backend.template;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateAudioClipRepository extends JpaRepository<TemplateAudioClip, String> {

  List<TemplateAudioClip> findByTemplateIdOrderByOrderAsc(String templateId);

  Optional<TemplateAudioClip> findByIdAndTemplateId(String id, String templateId);

  long countByTemplateId(String templateId);
}
