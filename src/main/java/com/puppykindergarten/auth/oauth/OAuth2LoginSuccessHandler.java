package com.puppykindergarten.auth.oauth;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.puppykindergarten.auth.token.AuthTokenService;
import com.puppykindergarten.auth.token.TokenPair;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * 카카오 로그인 성공 시 자체 JWT(Access/Refresh)를 발급해 응답한다.
 * 인가 요청(state) 저장에만 쓰인 세션은 폐기해, 이후 API 가 세션 쿠키로 인증되는 일이 없게 한다.
 */
@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final AuthTokenService authTokenService;
    private final ObjectMapper objectMapper;

    public OAuth2LoginSuccessHandler(AuthTokenService authTokenService, ObjectMapper objectMapper) {
        this.authTokenService = authTokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        KakaoOAuth2User user = (KakaoOAuth2User) authentication.getPrincipal();

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        TokenPair tokens = authTokenService.issue(user.getUserId(), user.getRole());

        LoginResponse body = new LoginResponse(tokens, user.getUserId(), user.getNickname(), user.getRole().name());

        response.setStatus(HttpServletResponse.SC_OK);
        // 토큰이 담긴 응답이 브라우저/프록시에 캐시되지 않게 한다.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), body);
    }

    /** 로그인 응답. 토큰 필드는 재발급 응답과 같은 {@link TokenPair} 를 펼쳐서 담는다. */
    public record LoginResponse(@JsonUnwrapped TokenPair tokens, Long userId, String nickname, String role) {
    }
}
