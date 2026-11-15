package com.puppykindergarten.config;

import com.puppykindergarten.auth.oauth.KakaoOAuth2UserService;
import com.puppykindergarten.auth.oauth.OAuth2LoginFailureHandler;
import com.puppykindergarten.auth.oauth.OAuth2LoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            KakaoOAuth2UserService kakaoOAuth2UserService,
                                            OAuth2LoginSuccessHandler successHandler,
                                            OAuth2LoginFailureHandler failureHandler,
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
                        .anyRequest().authenticated())
                .oauth2Login(oauth -> oauth
                        .userInfoEndpoint(userInfo -> userInfo.userService(kakaoOAuth2UserService))
                        .successHandler(successHandler)
                        .failureHandler(failureHandler))
                // 미인증 요청을 로그인 페이지로 리다이렉트하지 않고 401 JSON 으로 응답한다.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> errorResponseWriter.write(
                                response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다."))
                        .accessDeniedHandler((request, response, e) -> errorResponseWriter.write(
                                response, HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다.")));

        return http.build();
    }
}
