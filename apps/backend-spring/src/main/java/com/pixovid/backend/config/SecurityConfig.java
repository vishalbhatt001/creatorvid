package com.pixovid.backend.config;

import com.pixovid.backend.auth.JsonAuthEntryPoint;
import com.pixovid.backend.auth.OAuth2SuccessHandler;
import com.pixovid.backend.auth.SessionAuthenticationFilter;
import com.pixovid.backend.auth.SessionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Session-cookie auth (email/password + optional Google OAuth2), replicating better-auth's
 * behavior from the Node backend: cookie-based sessions (see {@link SessionService}), JSON 401s
 * instead of redirects/whitelabel pages, and CORS scoped to the configured frontend origin(s).
 */
@Configuration
public class SecurityConfig {

  private final AppProperties appProperties;
  private final SessionService sessionService;
  private final JsonAuthEntryPoint jsonAuthEntryPoint;
  private final OAuth2SuccessHandler oAuth2SuccessHandler;
  private final ClientRegistrationRepository clientRegistrationRepository;

  public SecurityConfig(
      AppProperties appProperties,
      SessionService sessionService,
      JsonAuthEntryPoint jsonAuthEntryPoint,
      OAuth2SuccessHandler oAuth2SuccessHandler,
      ClientRegistrationRepository clientRegistrationRepository) {
    this.appProperties = appProperties;
    this.sessionService = sessionService;
    this.jsonAuthEntryPoint = jsonAuthEntryPoint;
    this.oAuth2SuccessHandler = oAuth2SuccessHandler;
    this.clientRegistrationRepository = clientRegistrationRepository;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> {})
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(
                        "/health",
                        "/actuator/**",
                        "/v3/api-docs/**",
                        "/api/auth/**",
                        "/oauth2/**",
                        "/login/**",
                        "/api/credits/webhook")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(jsonAuthEntryPoint))
        .addFilterBefore(
            new SessionAuthenticationFilter(sessionService), UsernamePasswordAuthenticationFilter.class);

    if (appProperties.getAuth().googleConfigured()) {
      http.oauth2Login(
          oauth2 ->
              oauth2
                  .clientRegistrationRepository(clientRegistrationRepository)
                  .successHandler(oAuth2SuccessHandler));
    }

    return http.build();
  }
}
