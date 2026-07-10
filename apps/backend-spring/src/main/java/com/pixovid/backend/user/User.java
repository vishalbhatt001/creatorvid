package com.pixovid.backend.user;

import com.pixovid.backend.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "user")
@Getter
@Setter
public class User extends BaseEntity {

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "email", nullable = false, unique = true)
  private String email;

  @Column(name = "emailVerified", nullable = false)
  private boolean emailVerified = false;

  @Column(name = "image")
  private String image;

  /** {@code "user"} (default) or {@code "admin"}. Superadmin is not a stored role; it's an env allowlist check. */
  @Column(name = "role", nullable = false)
  private String role = "user";

  /** Spendable credit balance; {@code credit_transaction} is the append-only audit ledger. */
  @Column(name = "credits", nullable = false)
  private int credits = 0;
}
