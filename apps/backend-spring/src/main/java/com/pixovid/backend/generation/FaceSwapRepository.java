package com.pixovid.backend.generation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FaceSwapRepository extends JpaRepository<FaceSwap, String> {

  List<FaceSwap> findByUserIdOrderByCreatedAtDesc(String userId);

  Optional<FaceSwap> findByIdAndUserId(String id, String userId);
}
