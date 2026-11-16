package com.puppykindergarten.auth.oauth;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.puppykindergarten.auth.token.AuthTokenService;
import com.puppykindergarten.auth.token.TokenPair;
import com.puppykindergarten.config.ErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final AuthTokenService authTokenService;
    private final ObjectMapper objectMapper;
    private final ErrorResponseWriter errorResponseWriter;

    public OAuth2LoginSuccessHandler(AuthTokenService authTokenService, ObjectMapper objectMapper,
                                     ErrorResponseWriter errorResponseWriter) {
        this.authTokenService = authTokenService;
        this.objectMapper = objectMapper;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        KakaoOAuth2User user = (KakaoOAuth2User) authentication.getPrincipal();

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        TokenPair tokens;
        try {
            tokens = authTokenService.issue(user.getUserId(), user.getRole());
        } catch (RuntimeException e) {
            // 여기는 컨트롤러 밖이라 공통 예외 처리(GlobalExceptionHandler)를 거치지 않는다.
            // 인증은 성공했지만 토큰을 저장하지 못한 서버 오류이므로, 401 이 아닌 500 을 공통 형식으로 응답한다.
            log.error("로그인 토큰 발급 실패: userId={}", user.getUserId(), e);
            errorResponseWriter.write(response, HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR", "서버 오류가 발생했습니다.");
            return;
        }

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
