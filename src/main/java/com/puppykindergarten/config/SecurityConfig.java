package com.puppykindergarten.config;

import com.puppykindergarten.auth.jwt.JwtTokenProvider;
import com.puppykindergarten.auth.oauth.KakaoOAuth2UserService;
import com.puppykindergarten.auth.oauth.KakaoRestClients;
import com.puppykindergarten.auth.oauth.OAuth2LoginFailureHandler;
import com.puppykindergarten.auth.oauth.OAuth2LoginSuccessHandler;
import com.puppykindergarten.user.Role;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    /** Refresh Token 으로 자격을 확인하는 API. Access Token 이 만료된 뒤에도 호출할 수 있어야 한다. */
    private static final RequestMatcher REFRESH_TOKEN_ENDPOINTS = new OrRequestMatcher(
            new AntPathRequestMatcher("/api/auth/reissue", HttpMethod.POST.name()),
            new AntPathRequestMatcher("/api/auth/logout", HttpMethod.POST.name()));

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            KakaoOAuth2UserService kakaoOAuth2UserService,
                                            KakaoRestClients kakaoRestClients,
                                            OAuth2LoginSuccessHandler successHandler,
                                            OAuth2LoginFailureHandler failureHandler,
                                            JwtDecoder jwtDecoder,
                                            BearerTokenErrorEntryPoint authenticationEntryPoint,
                                            ErrorResponseWriter errorResponseWriter) throws Exception {
        http
                // 쿠키/세션이 아닌 Authorization 헤더로 인증하는 API 이므로 CSRF 보호 대상이 아니다.
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // 인증 상태를 세션에 저장하지 않는다. (인가 요청 state 보관용 세션은 로그인 성공 시 폐기)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/oauth2/authorization/**", "/login/oauth2/code/**", "/error").permitAll()
                        .requestMatchers(REFRESH_TOKEN_ENDPOINTS).permitAll()
                        // 홈은 견주/원장 역할이 있어야 한다. 응답 내용은 역할에 따라 컨트롤러에서 갈린다.
                        .requestMatchers("/api/home").hasAnyRole(
                                Arrays.stream(Role.values()).map(Role::name).toArray(String[]::new))
                        .anyRequest().authenticated())
                // 1) 카카오 로그인 → 자체 JWT 발급
                .oauth2Login(oauth -> oauth
                        .tokenEndpoint(token -> token.accessTokenResponseClient(kakaoRestClients.tokenResponseClient()))
                        .userInfoEndpoint(userInfo -> userInfo.userService(kakaoOAuth2UserService))
                        .successHandler(successHandler)
                        .failureHandler(failureHandler))
                // 2) 이후 요청은 Authorization: Bearer <자체 JWT> 로 인증
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .bearerTokenResolver(bearerTokenResolver())
                        .authenticationEntryPoint(authenticationEntryPoint))
                // 미인증 요청을 로그인 페이지로 리다이렉트하지 않고 401 JSON 으로 응답한다.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler((request, response, e) -> errorResponseWriter.write(
                                response, HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다.")));

        return http.build();
    }

    /** Bearer 토큰 검증에 쓰는 디코더. Access Token 만 통과시킨다. */
    @Bean
    JwtDecoder jwtDecoder(JwtTokenProvider jwtTokenProvider) {
        return jwtTokenProvider.accessTokenDecoder();
    }

    /**
     * 재발급·로그아웃 요청에서는 Authorization 헤더를 읽지 않는다.
     * 클라이언트가 모든 요청에 (만료된) Access Token 을 붙여 보내더라도 이 두 API 가 401 로 막히지 않게 한다.
     */
    private BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return request -> REFRESH_TOKEN_ENDPOINTS.matches(request) ? null : delegate.resolve(request);
    }

    /** JWT 의 role 클레임(OWNER/DIRECTOR)을 Spring Security 권한(ROLE_OWNER/ROLE_DIRECTOR)으로 바꾼다. */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString(JwtTokenProvider.CLAIM_ROLE);
            if (role == null || role.isBlank()) {
                return List.<GrantedAuthority>of();
            }
            return List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }
}
