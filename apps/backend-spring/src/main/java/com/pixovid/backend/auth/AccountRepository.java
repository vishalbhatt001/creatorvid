package com.pixovid.backend.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, String> {

  List<Account> findByUserId(String userId);

  Optional<Account> findByUserIdAndProviderId(String userId, String providerId);

  Optional<Account> findByProviderIdAndAccountId(String providerId, String accountId);
}
