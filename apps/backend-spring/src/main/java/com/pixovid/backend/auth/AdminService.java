package com.pixovid.backend.auth;

import com.pixovid.backend.common.ForbiddenException;
import com.pixovid.backend.config.AppProperties;
import com.pixovid.backend.user.User;
import com.pixovid.backend.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Port of apps/backend/src/middleware/requireAdmin.ts: lazy admin/superadmin promotion from env allowlists. */
@Service
public class AdminService {

  private final UserRepository users;
  private final AppProperties appProperties;

  public AdminService(UserRepository users, AppProperties appProperties) {
    this.users = users;
    this.appProperties = appProperties;
  }

  public boolean isSuperAdminEmail(String email) {
    return email != null && appProperties.getSuperadminEmails().contains(email.toLowerCase());
  }

  /**
   * Resolves whether a user is an admin, lazily promoting anyone whose email is in the
   * ADMIN_EMAILS (or SUPERADMIN_EMAILS) allowlist to the "admin" role.
   */
  @Transactional
  public boolean resolveIsAdmin(User user) {
    if ("admin".equals(user.getRole())) {
      return true;
    }
    String email = user.getEmail();
    boolean inAllowlist =
        (email != null && appProperties.getAdminEmails().contains(email.toLowerCase()))
            || isSuperAdminEmail(email);
    if (inAllowlist) {
      user.setRole("admin");
      users.save(user);
      return true;
    }
    return false;
  }

  /** Throws 403 unless the user is an admin (promoting on the way, per {@link #resolveIsAdmin}). */
  public void requireAdmin(User user) {
    if (!resolveIsAdmin(user)) {
      throw new ForbiddenException("Admin access required");
    }
  }
}
