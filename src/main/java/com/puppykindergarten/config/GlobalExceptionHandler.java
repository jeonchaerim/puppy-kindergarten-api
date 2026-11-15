package com.puppykindergarten.config;

import com.puppykindergarten.auth.token.InvalidRefreshTokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 컨트롤러 단계의 예외를 시큐리티 필터와 같은 형식({@link ApiError})으로 응답한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ApiError> handleInvalidRefreshToken(InvalidRefreshTokenException e) {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다.");
    }

    /**
     * 그 외 모든 예외. 스프링이 상태 코드를 정해 둔 예외(415, 405, 404, ResponseStatusException 등)는
     * 그 상태 코드를 그대로 쓰고, 나머지는 내부 정보를 드러내지 않고 500 으로 응답한다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOthers(Exception e) throws Exception {
        // 인증/인가 예외는 시큐리티 필터가 처리하도록 그대로 넘긴다.
        if (e instanceof AuthenticationException || e instanceof AccessDeniedException) {
            throw e;
        }
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatusCode statusCode = errorResponse.getStatusCode();
            HttpStatus status = HttpStatus.resolve(statusCode.value());
            String code = status != null ? status.name() : "ERROR";
            String message = e instanceof ResponseStatusException rse && rse.getReason() != null
                    ? rse.getReason()
                    : "요청을 처리할 수 없습니다.";
            return ResponseEntity.status(statusCode).body(new ApiError(code, message));
        }
        log.error("처리되지 않은 예외", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 오류가 발생했습니다.");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }
}
