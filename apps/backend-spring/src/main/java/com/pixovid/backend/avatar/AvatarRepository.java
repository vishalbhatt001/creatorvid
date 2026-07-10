package com.pixovid.backend.avatar;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AvatarRepository extends JpaRepository<Avatar, String> {

  List<Avatar> findByUserIdOrderByCreatedAtDesc(String userId);

  Optional<Avatar> findByIdAndUserId(String id, String userId);

  List<Avatar> findByIdIn(List<String> ids);
}
