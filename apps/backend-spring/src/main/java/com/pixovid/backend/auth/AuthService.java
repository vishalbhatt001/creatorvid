package com.pixovid.backend.auth;

import com.pixovid.backend.common.BadRequestException;
import com.pixovid.backend.common.UnauthorizedException;
import com.pixovid.backend.user.User;
import com.pixovid.backend.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Email/password registration and login, equivalent to better-auth's emailAndPassword provider. */
@Service
public class AuthService {

  private static final String CREDENTIAL_PROVIDER = "credential";

  private final UserRepository users;
  private final AccountRepository accounts;
  private final PasswordEncoder passwordEncoder;

  public AuthService(UserRepository users, AccountRepository accounts, PasswordEncoder passwordEncoder) {
    this.users = users;
    this.accounts = accounts;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional
  public User register(String name, String email, String rawPassword) {
    if (users.findByEmail(email).isPresent()) {
      throw new BadRequestException("Email already registered");
    }
    User user = new User();
    user.setName(name);
    user.setEmail(email);
    // No email server wired up (yet); mirror the Node backend's requireEmailVerification: false.
    user.setEmailVerified(false);
    user = users.save(user);

    Account account = new Account();
    account.setUser(user);
    account.setProviderId(CREDENTIAL_PROVIDER);
    account.setAccountId(user.getId());
    account.setPassword(passwordEncoder.encode(rawPassword));
    accounts.save(account);

    return user;
  }

  @Transactional(readOnly = true)
  public User login(String email, String rawPassword) {
    User user = users.findByEmail(email).orElseThrow(() -> new UnauthorizedException("Invalid credentials"));
    Account account =
        accounts
            .findByUserIdAndProviderId(user.getId(), CREDENTIAL_PROVIDER)
            .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));
    if (account.getPassword() == null || !passwordEncoder.matches(rawPassword, account.getPassword())) {
      throw new UnauthorizedException("Invalid credentials");
    }
    return user;
  }
}
