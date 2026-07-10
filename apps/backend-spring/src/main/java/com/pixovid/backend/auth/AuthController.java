package com.pixovid.backend.auth;

import com.pixovid.backend.auth.dto.LoginRequest;
import com.pixovid.backend.auth.dto.RegisterRequest;
import com.pixovid.backend.auth.dto.UserSummary;
import com.pixovid.backend.common.UnauthorizedException;
import com.pixovid.backend.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Custom session-cookie auth (email/password + Google OAuth2, the latter handled by Spring
 * Security's {@code /oauth2/authorization/google} + {@link OAuth2SuccessHandler}). Not a literal
 * port of better-auth's route shapes — the new frontend (M8+) talks to these directly.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthService authService;
  private final SessionService sessionService;

  public AuthController(AuthService authService, SessionService sessionService) {
    this.authService = authService;
    this.sessionService = sessionService;
  }

  @PostMapping("/register")
  public UserSummary register(
      @Valid @RequestBody RegisterRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse) {
    User user = authService.register(request.name(), request.email(), request.password());
    issueSessionCookie(user, httpRequest, httpResponse);
    return UserSummary.of(user);
  }

  @PostMapping("/login")
  public UserSummary login(
      @Valid @RequestBody LoginRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse) {
    User user = authService.login(request.email(), request.password());
    issueSessionCookie(user, httpRequest, httpResponse);
    return UserSummary.of(user);
  }

  @PostMapping("/logout")
  public void logout(
      @CookieValue(name = "pixovid_session", required = false) String token, HttpServletResponse response) {
    sessionService.invalidate(token);
    response.addHeader(HttpHeaders.SET_COOKIE, sessionService.buildClearingCookie().toString());
  }

  @GetMapping("/session")
  public UserSummary session(@AuthenticationPrincipal User user) {
    if (user == null) {
      throw new UnauthorizedException("Unauthorized");
    }
    return UserSummary.of(user);
  }

  private void issueSessionCookie(User user, HttpServletRequest request, HttpServletResponse response) {
    Session session = sessionService.createSession(user, request);
    ResponseCookie cookie = sessionService.buildCookie(session.getToken());
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }
}
