package com.pixovid.backend.auth;

import com.pixovid.backend.user.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Populates the SecurityContext from the session cookie, equivalent to Express's requireAuth reading better-auth's session. */
public class SessionAuthenticationFilter extends OncePerRequestFilter {

  private final SessionService sessionService;

  public SessionAuthenticationFilter(SessionService sessionService) {
    this.sessionService = sessionService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    findCookie(request, sessionService.cookieName())
        .flatMap(sessionService::resolveUser)
        .ifPresent(
            user -> {
              List<GrantedAuthority> authorities =
                  "admin".equals(user.getRole())
                      ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                      : List.of(new SimpleGrantedAuthority("ROLE_USER"));
              var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
              SecurityContextHolder.getContext().setAuthentication(authentication);
            });
    filterChain.doFilter(request, response);
  }

  private Optional<String> findCookie(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    for (Cookie cookie : cookies) {
      if (name.equals(cookie.getName())) {
        return Optional.ofNullable(cookie.getValue());
      }
    }
    return Optional.empty();
  }
}
