package com.pixovid.backend.credits;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, String> {

  List<CreditTransaction> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

  List<CreditTransaction> findByReferenceTypeAndReferenceId(String referenceType, String referenceId);
}
