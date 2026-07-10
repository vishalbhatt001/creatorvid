package com.pixovid.backend.auth;

import com.pixovid.backend.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "verification")
@Getter
@Setter
public class Verification extends BaseEntity {

  @Column(name = "identifier", nullable = false)
  private String identifier;

  @Column(name = "value", nullable = false)
  private String value;

  @Column(name = "expiresAt", nullable = false)
  private LocalDateTime expiresAt;
}
