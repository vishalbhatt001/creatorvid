package com.pixovid.backend.template;

import com.pixovid.backend.generation.GenerationStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TemplateRenderRepository extends JpaRepository<TemplateRender, String> {

  List<TemplateRender> findByUserIdOrderByCreatedAtDesc(String userId);

  List<TemplateRender> findByTemplateIdAndUserIdOrderByCreatedAtDesc(String templateId, String userId);

  Optional<TemplateRender> findByIdAndUserId(String id, String userId);

  List<TemplateRender> findByStatus(GenerationStatus status);

  /** Eagerly fetches the template so callers can read its name without an open Hibernate session. */
  @Query("SELECT r FROM TemplateRender r JOIN FETCH r.template WHERE r.user.id = :userId ORDER BY r.createdAt DESC")
  List<TemplateRender> findByUserIdFetchTemplateOrderByCreatedAtDesc(@Param("userId") String userId);

  @Query("SELECT r FROM TemplateRender r JOIN FETCH r.template WHERE r.id = :id AND r.user.id = :userId")
  Optional<TemplateRender> findByIdAndUserIdFetchTemplate(@Param("id") String id, @Param("userId") String userId);
}
