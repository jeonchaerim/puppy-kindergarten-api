package com.puppykindergarten.auth.oauth;

import com.puppykindergarten.config.ErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/**
 * 카카오 로그인이 끝까지 가지 못했을 때의 응답. 원인에 따라 상태 코드를 구분한다.
 *
 * <ul>
 *   <li>401 KAKAO_LOGIN_FAILED: 사용자 취소, state 불일치, 카카오가 인가 코드를 거부 등 인증 실패</li>
 *   <li>503 KAKAO_UNAVAILABLE: 카카오 서버에 연결하지 못했거나 제한 시간 안에 응답이 없음</li>
 *   <li>500 INTERNAL_ERROR: 우리 서버(DB 등)의 문제</li>
 * </ul>
 *
 * 응답에는 상세 원인을 싣지 않는다. 로그에도 카카오가 보낸 메시지 원문 대신 오류 코드만 남긴다.
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
        if (exception instanceof InternalAuthenticationServiceException) {
            log.error("카카오 로그인 처리 중 서버 오류", exception);
            errorResponseWriter.write(response, HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR", "서버 오류가 발생했습니다.");
            return;
        }
        if (causedByNetworkFailure(exception)) {
            log.warn("카카오 서버 호출 실패(연결 불가 또는 제한 시간 초과): code={}", errorCodeOf(exception));
            errorResponseWriter.write(response, HttpStatus.SERVICE_UNAVAILABLE,
                    "KAKAO_UNAVAILABLE", "카카오 서버와 통신할 수 없습니다. 잠시 후 다시 시도해 주세요.");
            return;
        }
        log.warn("카카오 로그인 실패: code={}", errorCodeOf(exception));
        errorResponseWriter.write(response, HttpStatus.UNAUTHORIZED, "KAKAO_LOGIN_FAILED", "카카오 로그인에 실패했습니다.");
    }

    private boolean causedByNetworkFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private String errorCodeOf(AuthenticationException exception) {
        return exception instanceof OAuth2AuthenticationException oauth2
                ? oauth2.getError().getErrorCode()
                : exception.getClass().getSimpleName();
    }
}
