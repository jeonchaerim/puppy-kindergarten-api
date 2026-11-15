package com.puppykindergarten.auth.token;

/**
 * 클라이언트에 내려주는 토큰 묶음.
 *
 * @param accessTokenExpiresIn Access Token 유효 시간(초)
 */
public record TokenPair(String tokenType, String accessToken, long accessTokenExpiresIn, String refreshToken) {

    public static TokenPair bearer(String accessToken, long accessTokenExpiresIn, String refreshToken) {
        return new TokenPair("Bearer", accessToken, accessTokenExpiresIn, refreshToken);
    }
}
