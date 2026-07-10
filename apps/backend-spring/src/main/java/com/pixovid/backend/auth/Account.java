package com.pixovid.backend.auth;

import com.pixovid.backend.common.BaseEntity;
import com.pixovid.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** An auth method linked to a user: email/password (password set, providerId="credential") or Google OAuth2. */
@Entity
@Table(name = "account")
@Getter
@Setter
public class Account extends BaseEntity {

  @Column(name = "accountId", nullable = false)
  private String accountId;

  @Column(name = "providerId", nullable = false)
  private String providerId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "userId", nullable = false)
  private User user;

  @Column(name = "accessToken")
  private String accessToken;

  @Column(name = "refreshToken")
  private String refreshToken;

  @Column(name = "idToken")
  private String idToken;

  @Column(name = "accessTokenExpiresAt")
  private LocalDateTime accessTokenExpiresAt;

  @Column(name = "refreshTokenExpiresAt")
  private LocalDateTime refreshTokenExpiresAt;

  @Column(name = "scope")
  private String scope;

  /** Bcrypt hash for the {@code "credential"} (email/password) provider; null for OAuth providers. */
  @Column(name = "password")
  private String password;
}
