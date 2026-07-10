package com.pixovid.backend.auth;

import com.pixovid.backend.config.AppProperties;
import com.pixovid.backend.user.User;
import com.pixovid.backend.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * On a successful Google login, find-or-create the {@code user}/{@code account} rows (mirroring
 * better-auth's social sign-in), issue our own session cookie, and redirect back to the frontend
 * — equivalent to what better-auth does internally after its own OAuth callback.
 */
@Component
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

  private final UserRepository users;
  private final AccountRepository accounts;
  private final SessionService sessionService;
  private final AppProperties appProperties;
  private final ObjectProvider<OAuth2AuthorizedClientService> authorizedClientService;

  public OAuth2SuccessHandler(
      UserRepository users,
      AccountRepository accounts,
      SessionService sessionService,
      AppProperties appProperties,
      ObjectProvider<OAuth2AuthorizedClientService> authorizedClientService) {
    this.users = users;
    this.accounts = accounts;
    this.sessionService = sessionService;
    this.appProperties = appProperties;
    this.authorizedClientService = authorizedClientService;
  }

  @Override
  @Transactional
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
    OidcUser oidcUser = (OidcUser) oauthToken.getPrincipal();

    String email = oidcUser.getEmail();
    String googleSub = oidcUser.getSubject();

    User user =
        users
            .findByEmail(email)
            .orElseGet(
                () -> {
                  User created = new User();
                  created.setName(oidcUser.getFullName() != null ? oidcUser.getFullName() : email);
                  created.setEmail(email);
                  created.setEmailVerified(Boolean.TRUE.equals(oidcUser.getEmailVerified()));
                  created.setImage(oidcUser.getPicture());
                  return users.save(created);
                });

    Account account =
        accounts.findByProviderIdAndAccountId("google", googleSub).orElseGet(Account::new);
    account.setUser(user);
    account.setProviderId("google");
    account.setAccountId(googleSub);
    account.setIdToken(oidcUser.getIdToken().getTokenValue());

    OAuth2AuthorizedClientService clientService = authorizedClientService.getIfAvailable();
    OAuth2AuthorizedClient client =
        clientService == null ? null : clientService.loadAuthorizedClient("google", oauthToken.getName());
    if (client != null && client.getAccessToken() != null) {
      account.setAccessToken(client.getAccessToken().getTokenValue());
      if (client.getAccessToken().getExpiresAt() != null) {
        account.setAccessTokenExpiresAt(
            LocalDateTime.ofInstant(client.getAccessToken().getExpiresAt(), ZoneOffset.UTC));
      }
      if (client.getAccessToken().getScopes() != null) {
        account.setScope(String.join(",", client.getAccessToken().getScopes()));
      }
      if (client.getRefreshToken() != null) {
        account.setRefreshToken(client.getRefreshToken().getTokenValue());
      }
    }
    accounts.save(account);

    Session session = sessionService.createSession(user, request);
    ResponseCookie cookie = sessionService.buildCookie(session.getToken());
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

    String redirectUrl =
        appProperties.getFrontendUrl().isEmpty() ? "/" : appProperties.getFrontendUrl().get(0);
    response.sendRedirect(redirectUrl);
  }
}
