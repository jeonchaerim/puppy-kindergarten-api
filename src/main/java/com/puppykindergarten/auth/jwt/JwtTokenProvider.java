package com.puppykindergarten.auth.jwt;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.puppykindergarten.user.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * 자체 JWT(HS256) 발급과 검증.
 *
 * <ul>
 *   <li>sub: 사용자 식별자(users.id)</li>
 *   <li>role: OWNER / DIRECTOR (Access Token 에만 포함)</li>
 *   <li>token_type: access / refresh — Refresh Token 을 API 인증에 쓰지 못하게 구분한다.</li>
 * </ul>
 */
@Component
public class JwtTokenProvider {

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_TOKEN_TYPE = "token_type";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final JwtProperties properties;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final JwtDecoder accessTokenDecoder;
    private final JwtDecoder refreshTokenDecoder;

    public JwtTokenProvider(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;

        SecretKey key = new SecretKeySpec(properties.secretBytes(), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.accessTokenDecoder = decoderFor(key, TYPE_ACCESS);
        this.refreshTokenDecoder = decoderFor(key, TYPE_REFRESH);
    }

    public String createAccessToken(Long userId, Role role) {
        Instant now = clock.instant();
        JwtClaimsSet claims = baseClaims(userId, now, properties.accessTtl())
                .claim(CLAIM_TOKEN_TYPE, TYPE_ACCESS)
                .claim(CLAIM_ROLE, role.name())
                .build();
        return encode(claims);
    }

    public IssuedRefreshToken createRefreshToken(Long userId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.refreshTtl());
        JwtClaimsSet claims = baseClaims(userId, now, properties.refreshTtl())
                .claim(CLAIM_TOKEN_TYPE, TYPE_REFRESH)
                .build();
        return new IssuedRefreshToken(encode(claims), expiresAt);
    }

    /** API 인증(Bearer)에 쓰는 디코더. 서명, 만료, issuer, token_type=access 를 검증한다. */
    public JwtDecoder accessTokenDecoder() {
        return accessTokenDecoder;
    }

    /** Refresh Token 의 서명, 만료, issuer, token_type=refresh 를 검증한다. */
    public Jwt decodeRefreshToken(String refreshToken) throws JwtException {
        return refreshTokenDecoder.decode(refreshToken);
    }

    public Duration accessTtl() {
        return properties.accessTtl();
    }

    private JwtClaimsSet.Builder baseClaims(Long userId, Instant issuedAt, Duration ttl) {
        return JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ttl));
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private JwtDecoder decoderFor(SecretKey key, String tokenType) {
        // 알고리즘을 HS256 으로 고정한다. (alg=none 이나 다른 알고리즘으로 바꿔치기한 토큰 거부)
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                new JwtClaimValidator<String>(CLAIM_TOKEN_TYPE, tokenType::equals)));
        return decoder;
    }

    public record IssuedRefreshToken(String value, Instant expiresAt) {
    }
}
