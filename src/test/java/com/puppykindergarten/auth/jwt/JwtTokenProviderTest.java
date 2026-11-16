package com.puppykindergarten.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.puppykindergarten.auth.jwt.JwtTokenProvider.IssuedRefreshToken;
import com.puppykindergarten.user.Role;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

class JwtTokenProviderTest {

    private static final String SECRET = base64("unit-test-secret-key-0123456789-abcdef");
    private static final String OTHER_SECRET = base64("another-secret-key-0123456789-abcdefgh");
    private static final String ISSUER = "puppy-kindergarten-api";

    private final JwtProperties properties = new JwtProperties(SECRET, ISSUER, Duration.ofMinutes(15), Duration.ofDays(14));
    private final JwtTokenProvider provider = new JwtTokenProvider(properties, Clock.systemUTC());

    @Test
    @DisplayName("Access Token 에는 사용자 식별자와 역할이 담긴다")
    void accessTokenCarriesUserIdAndRole() {
        String token = provider.createAccessToken(42L, Role.DIRECTOR);

        Jwt jwt = provider.accessTokenDecoder().decode(token);

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("DIRECTOR");
        assertThat(jwt.getClaimAsString("token_type")).isEqualTo("access");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("Refresh Token 은 설정한 유효 시간으로 발급되고 역할을 담지 않는다")
    void refreshTokenHasConfiguredTtlAndNoRole() {
        IssuedRefreshToken refreshToken = provider.createRefreshToken(42L);

        Jwt jwt = provider.decodeRefreshToken(refreshToken.value());

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("token_type")).isEqualTo("refresh");
        assertThat(jwt.hasClaim("role")).isFalse();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofDays(14));
        assertThat(refreshToken.expiresAt().getEpochSecond()).isEqualTo(jwt.getExpiresAt().getEpochSecond());
    }

    @Test
    @DisplayName("같은 사용자에게 발급해도 토큰은 매번 다르다 (jti)")
    void tokensAreUniquePerIssue() {
        assertThat(provider.createRefreshToken(42L).value()).isNotEqualTo(provider.createRefreshToken(42L).value());
    }

    @Test
    @DisplayName("만료된 Access Token 은 거부한다")
    void rejectsExpiredAccessToken() {
        JwtTokenProvider past = new JwtTokenProvider(properties, clockAt(Instant.now().minus(Duration.ofHours(1))));
        String expired = past.createAccessToken(42L, Role.OWNER);

        assertThatThrownBy(() -> provider.accessTokenDecoder().decode(expired))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("만료 시각이 지나면 허용 오차 없이 바로 거부한다")
    void rejectsJustExpiredTokenWithoutLeeway() {
        // 유효 시간 15분짜리 토큰을 15분 5초 전에 발급 = 5초 전에 만료
        JwtTokenProvider past = new JwtTokenProvider(properties,
                clockAt(Instant.now().minus(Duration.ofMinutes(15)).minusSeconds(5)));

        assertThatThrownBy(() -> provider.accessTokenDecoder().decode(past.createAccessToken(42L, Role.OWNER)))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("다른 키로 서명한 토큰은 거부한다")
    void rejectsTokenSignedWithAnotherKey() {
        JwtTokenProvider attacker = new JwtTokenProvider(
                new JwtProperties(OTHER_SECRET, ISSUER, Duration.ofMinutes(15), Duration.ofDays(14)), Clock.systemUTC());
        String forged = attacker.createAccessToken(42L, Role.DIRECTOR);

        assertThatThrownBy(() -> provider.accessTokenDecoder().decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("페이로드를 변조한 토큰(역할 바꿔치기)은 거부한다")
    void rejectsTamperedPayload() {
        String token = provider.createAccessToken(42L, Role.OWNER);
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String tamperedPayload = urlBase64(payload.replace("\"OWNER\"", "\"DIRECTOR\""));
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThat(tamperedPayload).isNotEqualTo(parts[1]);
        assertThatThrownBy(() -> provider.accessTokenDecoder().decode(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("서명 없는 토큰(alg=none)은 거부한다")
    void rejectsUnsignedToken() {
        String token = provider.createAccessToken(42L, Role.OWNER);
        String unsigned = urlBase64("{\"alg\":\"none\"}") + "." + token.split("\\.")[1] + ".";

        assertThatThrownBy(() -> provider.accessTokenDecoder().decode(unsigned)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Refresh Token 은 Access Token 으로 쓸 수 없고, 그 반대도 마찬가지다")
    void tokenTypesAreNotInterchangeable() {
        String accessToken = provider.createAccessToken(42L, Role.OWNER);
        String refreshToken = provider.createRefreshToken(42L).value();

        assertThatThrownBy(() -> provider.accessTokenDecoder().decode(refreshToken)).isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> provider.decodeRefreshToken(accessToken)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("issuer 가 다른 토큰은 거부한다")
    void rejectsOtherIssuer() {
        JwtTokenProvider other = new JwtTokenProvider(
                new JwtProperties(SECRET, "someone-else", Duration.ofMinutes(15), Duration.ofDays(14)), Clock.systemUTC());

        assertThatThrownBy(() -> provider.accessTokenDecoder().decode(other.createAccessToken(42L, Role.OWNER)))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("서명키가 없거나 32바이트보다 짧으면 설정 단계에서 실패한다")
    void rejectsWeakSecret() {
        assertThatThrownBy(() -> new JwtProperties(base64("short-key"), ISSUER, Duration.ofMinutes(15), Duration.ofDays(14)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtProperties(" ", ISSUER, Duration.ofMinutes(15), Duration.ofDays(14)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtProperties("not base64 !!", ISSUER, Duration.ofMinutes(15), Duration.ofDays(14)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("설정 객체를 출력해도 서명키는 노출되지 않는다")
    void toStringHidesSecret() {
        assertThat(properties.toString()).doesNotContain(SECRET);
    }

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static String base64(String raw) {
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String urlBase64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
