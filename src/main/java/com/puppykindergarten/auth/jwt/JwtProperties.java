package com.puppykindergarten.auth.jwt;

import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 발급/검증 설정. 서명키는 환경변수 JWT_SECRET 으로만 주입한다.
 *
 * @param secret     Base64 로 인코딩한 HMAC-SHA256 서명키 (디코딩 기준 32바이트 이상)
 * @param issuer     iss 클레임 값
 * @param accessTtl  Access Token 유효 시간
 * @param refreshTtl Refresh Token 유효 시간
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, String issuer, Duration accessTtl, Duration refreshTtl) {

    private static final int MIN_KEY_BYTES = 32; // HS256 은 256비트 이상의 키가 필요하다.

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT 서명키(JWT_SECRET)가 설정되지 않았습니다.");
        }
        if (decode(secret).length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("JWT 서명키는 Base64 디코딩 기준 32바이트 이상이어야 합니다.");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT issuer 가 설정되지 않았습니다.");
        }
        if (accessTtl == null || accessTtl.isZero() || accessTtl.isNegative()
                || refreshTtl == null || refreshTtl.isZero() || refreshTtl.isNegative()) {
            throw new IllegalArgumentException("JWT 유효 시간은 0보다 커야 합니다.");
        }
    }

    public byte[] secretBytes() {
        return decode(secret);
    }

    private static byte[] decode(String secret) {
        try {
            return Base64.getDecoder().decode(secret.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("JWT 서명키는 Base64 형식이어야 합니다.");
        }
    }

    /** 서명키가 로그나 actuator 등에 그대로 찍히지 않도록 가린다. */
    @Override
    public String toString() {
        return "JwtProperties[secret=****, issuer=" + issuer + ", accessTtl=" + accessTtl + ", refreshTtl=" + refreshTtl + "]";
    }
}
