package com.pixovid.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

/**
 * Google's OIDC client registration, present only when GOOGLE_CLIENT_ID/SECRET are configured
 * (mirrors the Node backend's `socialProviders` being entirely omitted otherwise). Spring
 * Security's oauth2-client autoconfiguration unconditionally requires *a* ClientRegistrationRepository
 * bean to exist once the starter is on the classpath, so we always register one — an empty
 * (no-registrations) repository when Google isn't configured, so {@code /oauth2/authorization/google}
 * is simply unavailable rather than the app failing to boot.
 */
@Configuration
public class OAuth2Config {

  private final AppProperties appProperties;

  public OAuth2Config(AppProperties appProperties) {
    this.appProperties = appProperties;
  }

  @Bean
  public ClientRegistrationRepository clientRegistrationRepository() {
    if (!appProperties.getAuth().googleConfigured()) {
      return registrationId -> null;
    }
    return new InMemoryClientRegistrationRepository(googleClientRegistration());
  }

  @Bean
  public OAuth2AuthorizedClientService authorizedClientService(
      ClientRegistrationRepository clientRegistrationRepository) {
    return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
  }

  /** Google's well-known OIDC endpoints, spelled out explicitly (CommonOAuth2Provider was removed from this Spring Security version). */
  ClientRegistration googleClientRegistration() {
    return ClientRegistration.withRegistrationId("google")
        .clientId(appProperties.getAuth().getGoogleClientId())
        .clientSecret(appProperties.getAuth().getGoogleClientSecret())
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/{action}/oauth2/code/{registrationId}")
        .scope("openid", "profile", "email")
        .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
        .tokenUri("https://www.googleapis.com/oauth2/v4/token")
        .userInfoUri("https://www.googleapis.com/oauth2/v3/userinfo")
        .userNameAttributeName(IdTokenClaimNames.SUB)
        .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
        .issuerUri("https://accounts.google.com")
        .clientName("Google")
        .build();
  }
}
