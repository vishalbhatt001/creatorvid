package com.pixovid.backend.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, String> {

  Optional<User> findByEmail(String email);

  /** Conditional decrement so concurrent requests can't drive the balance negative; returns rows affected (0 or 1). */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("UPDATE User u SET u.credits = u.credits - :amount WHERE u.id = :userId AND u.credits >= :amount")
  int decrementCreditsIfEnough(@Param("userId") String userId, @Param("amount") int amount);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("UPDATE User u SET u.credits = u.credits + :amount WHERE u.id = :userId")
  int incrementCredits(@Param("userId") String userId, @Param("amount") int amount);
}
