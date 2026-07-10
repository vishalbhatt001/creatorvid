package com.pixovid.backend.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<Session, String> {

  /** Eagerly fetches the owning user so it's usable outside the request's transaction (open-in-view is off). */
  @Query("SELECT s FROM Session s JOIN FETCH s.user WHERE s.token = :token")
  Optional<Session> findByTokenFetchUser(@Param("token") String token);

  void deleteByToken(String token);
}
