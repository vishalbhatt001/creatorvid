package com.pixovid.backend.auth;

import com.pixovid.backend.user.User;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Port of apps/backend/src/routes/me.ts: current user's profile + admin status + credit balance
 * (used by the frontend to gate admin UI and show the credit balance in the navbar).
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

  private final AdminService adminService;

  public MeController(AdminService adminService) {
    this.adminService = adminService;
  }

  @GetMapping
  public Map<String, Object> me(@AuthenticationPrincipal User user) {
    boolean isAdmin = adminService.resolveIsAdmin(user);
    return Map.of(
        "id", user.getId(),
        "email", user.getEmail(),
        "isAdmin", isAdmin,
        "isSuperAdmin", adminService.isSuperAdminEmail(user.getEmail()),
        "credits", user.getCredits());
  }
}
