package com.pixovid.backend.template;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateRepository extends JpaRepository<Template, String> {

  List<Template> findByPublishedTrue();

  Optional<Template> findByIdAndPublishedTrue(String id);

  List<Template> findByCreatorId(String creatorId);

  Optional<Template> findByIdAndCreatorId(String id, String creatorId);
}
