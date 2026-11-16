package com.puppykindergarten.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 인증 실패 시 401 JSON 응답.
 * 만료된 토큰은 TOKEN_EXPIRED 로 구분해 클라이언트가 재발급을 시도할 수 있게 하고,
 * 그 외(위조, 형식 오류 등)는 이유를 드러내지 않고 INVALID_TOKEN 으로 응답한다.
 */
@Component
public class BearerTokenErrorEntryPoint implements AuthenticationEntryPoint {

    private final ErrorResponseWriter errorResponseWriter;

    public BearerTokenErrorEntryPoint(ErrorResponseWriter errorResponseWriter) {
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");

        if (request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
            errorResponseWriter.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.");
        } else if (isExpired(authException)) {
            errorResponseWriter.write(response, HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "토큰이 만료되었습니다.");
        } else {
            errorResponseWriter.write(response, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "유효하지 않은 토큰입니다.");
        }
    }

    private boolean isExpired(AuthenticationException exception) {
        return exception.getCause() instanceof JwtValidationException validation
                && validation.getErrors().stream()
                .anyMatch(error -> error.getDescription() != null && error.getDescription().contains("expired"));
    }
}
