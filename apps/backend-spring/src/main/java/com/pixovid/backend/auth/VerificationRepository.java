package com.pixovid.backend.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationRepository extends JpaRepository<Verification, String> {

  Optional<Verification> findByIdentifier(String identifier);
}
