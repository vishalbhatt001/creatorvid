package com.pixovid.backend.auth;

import com.pixovid.backend.config.AppProperties;
import com.pixovid.backend.user.User;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cookie-based sessions, behaviorally equivalent to the Node backend's better-auth setup: an
 * HttpOnly opaque token in the {@code session} table, {@code SameSite=None; Secure} when the
 * backend is served over https (so a frontend on a different registrable domain can still send
 * the cookie), sliding expiry refreshed on use.
 */
@Service
public class SessionService {

  private static final String COOKIE_NAME = "pixovid_session";
  private static final Duration SESSION_TTL = Duration.ofDays(7);
  private static final Duration REFRESH_THRESHOLD = Duration.ofDays(1);

  private final SessionRepository sessions;
  private final AppProperties appProperties;
  private final SecureRandom random = new SecureRandom();

  public SessionService(SessionRepository sessions, AppProperties appProperties) {
    this.sessions = sessions;
    this.appProperties = appProperties;
  }

  @Transactional
  public Session createSession(User user, HttpServletRequest request) {
    Session session = new Session();
    session.setUser(user);
    session.setToken(generateToken());
    session.setExpiresAt(LocalDateTime.now().plus(SESSION_TTL));
    session.setIpAddress(request.getRemoteAddr());
    session.setUserAgent(request.getHeader("User-Agent"));
    return sessions.save(session);
  }

  /** Resolves the session's user, deleting it if expired and sliding the expiry forward on use. */
  @Transactional
  public Optional<User> resolveUser(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    Optional<Session> found = sessions.findByTokenFetchUser(token);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    Session session = found.get();
    LocalDateTime now = LocalDateTime.now();
    if (session.getExpiresAt().isBefore(now)) {
      sessions.delete(session);
      return Optional.empty();
    }
    if (session.getExpiresAt().isBefore(now.plus(REFRESH_THRESHOLD))) {
      session.setExpiresAt(now.plus(SESSION_TTL));
      sessions.save(session);
    }
    return Optional.of(session.getUser());
  }

  @Transactional
  public void invalidate(String token) {
    if (token != null && !token.isBlank()) {
      sessions.deleteByToken(token);
    }
  }

  public String cookieName() {
    return COOKIE_NAME;
  }

  public ResponseCookie buildCookie(String token) {
    return cookieBuilder(token, SESSION_TTL).build();
  }

  public ResponseCookie buildClearingCookie() {
    return cookieBuilder("", Duration.ZERO).build();
  }

  private ResponseCookie.ResponseCookieBuilder cookieBuilder(String token, Duration maxAge) {
    boolean crossSite =
        appProperties.getBackendUrl() != null && appProperties.getBackendUrl().startsWith("https://");
    ResponseCookie.ResponseCookieBuilder builder =
        ResponseCookie.from(COOKIE_NAME, token).httpOnly(true).path("/").maxAge(maxAge);
    return crossSite ? builder.sameSite("None").secure(true) : builder.sameSite("Lax").secure(false);
  }

  private String generateToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
