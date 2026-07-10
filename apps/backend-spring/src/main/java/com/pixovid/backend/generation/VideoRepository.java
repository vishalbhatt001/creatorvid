package com.pixovid.backend.generation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoRepository extends JpaRepository<Video, String> {

  List<Video> findByUserIdOrderByCreatedAtDesc(String userId);

  Optional<Video> findByIdAndUserId(String id, String userId);
}
