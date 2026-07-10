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

@Entity
@Table(name = "session")
@Getter
@Setter
public class Session extends BaseEntity {

  @Column(name = "expiresAt", nullable = false)
  private LocalDateTime expiresAt;

  @Column(name = "token", nullable = false, unique = true)
  private String token;

  @Column(name = "ipAddress")
  private String ipAddress;

  @Column(name = "userAgent")
  private String userAgent;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "userId", nullable = false)
  private User user;
}
