package com.puppykindergarten.auth.oauth;

import com.puppykindergarten.config.ErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * 카카오 로그인 실패(사용자 취소, state 불일치, 토큰 교환 실패 등) 시 401 JSON 을 내려준다.
 * 상세 원인은 로그에만 남기고 응답에는 노출하지 않는다.
 */
@Component
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginFailureHandler.class);

    private final ErrorResponseWriter errorResponseWriter;

    public OAuth2LoginFailureHandler(ErrorResponseWriter errorResponseWriter) {
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.warn("카카오 로그인 실패: {}", exception.getMessage());
        errorResponseWriter.write(response, HttpStatus.UNAUTHORIZED, "KAKAO_LOGIN_FAILED", "카카오 로그인에 실패했습니다.");
    }
}
