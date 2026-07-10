package com.pixovid.backend.generation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageRepository extends JpaRepository<Image, String> {

  List<Image> findByUserIdOrderByCreatedAtDesc(String userId);

  Optional<Image> findByIdAndUserId(String id, String userId);
}
