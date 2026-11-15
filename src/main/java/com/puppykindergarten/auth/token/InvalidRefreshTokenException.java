package com.puppykindergarten.auth.token;

/**
 * Refresh Token 이 유효하지 않을 때(위조, 만료, 이미 사용됨, 로그아웃됨).
 * 어떤 이유인지는 응답에 구분해서 드러내지 않는다.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("유효하지 않은 리프레시 토큰입니다.");
    }
}
