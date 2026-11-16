package com.puppykindergarten.auth.jwt;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.puppykindergarten.auth.KakaoLoginService;
import com.puppykindergarten.user.Role;
import com.puppykindergarten.user.User;
import com.puppykindergarten.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Authorization: Bearer <JWT> 검증을 실제 필터 체인으로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BearerAuthenticationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtTokenProvider jwtTokenProvider;

    @Autowired
    JwtProperties jwtProperties;

    @Autowired
    KakaoLoginService kakaoLoginService;

    @Autowired
    UserRepository userRepository;

    User owner;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        owner = kakaoLoginService.loginOrRegister(1001L, "초코아빠");
    }

    @Test
    @DisplayName("유효한 Access Token 이면 인증되고 토큰의 사용자로 식별된다")
    void validAccessTokenAuthenticates() throws Exception {
        String token = jwtTokenProvider.createAccessToken(owner.getId(), owner.getRole());

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(owner.getId()))
                .andExpect(jsonPath("$.kakaoId").value(1001))
                .andExpect(jsonPath("$.nickname").value("초코아빠"))
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    @DisplayName("토큰이 없으면 401 UNAUTHORIZED")
    void missingTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("만료된 토큰은 401 TOKEN_EXPIRED")
    void expiredTokenIsRejected() throws Exception {
        Clock oneHourAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        String expired = new JwtTokenProvider(jwtProperties, oneHourAgo).createAccessToken(owner.getId(), Role.OWNER);

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("다른 키로 서명한 토큰은 401 INVALID_TOKEN")
    void forgedTokenIsRejected() throws Exception {
        String otherSecret = Base64.getEncoder()
                .encodeToString("attacker-secret-key-0123456789-abcdef".getBytes(StandardCharsets.UTF_8));
        JwtProperties attackerProperties = new JwtProperties(
                otherSecret, jwtProperties.issuer(), jwtProperties.accessTtl(), jwtProperties.refreshTtl());
        String forged = new JwtTokenProvider(attackerProperties, Clock.systemUTC())
                .createAccessToken(owner.getId(), Role.DIRECTOR);

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("Refresh Token 을 Bearer 로 보내면 401 INVALID_TOKEN")
    void refreshTokenCannotBeUsedAsBearer() throws Exception {
        String refreshToken = jwtTokenProvider.createRefreshToken(owner.getId()).value();

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("컨트롤러에서 난 오류(404)도 공통 에러 형식으로 응답한다")
    void controllerErrorUsesCommonFormat() throws Exception {
        String tokenOfDeletedUser = jwtTokenProvider.createAccessToken(999_999L, Role.OWNER);

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOfDeletedUser))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("사용자를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("서명은 유효하지만 exp 가 없는 토큰은 401 INVALID_TOKEN (만료 없는 토큰 차단)")
    void tokenWithoutExpiryIsRejected() throws Exception {
        String token = signedToken(claims -> claims.subject(String.valueOf(owner.getId())).issuedAt(Instant.now()));

        mockMvc.perform(get("/api/home").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("서명은 유효하지만 sub 가 없거나 숫자가 아닌 토큰은 500 이 아니라 401 INVALID_TOKEN")
    void tokenWithoutValidSubjectIsRejected() throws Exception {
        Instant now = Instant.now();
        String noSubject = signedToken(claims -> claims.issuedAt(now).expiresAt(now.plusSeconds(60)));
        String textSubject = signedToken(claims -> claims.subject("not-a-number").issuedAt(now).expiresAt(now.plusSeconds(60)));
        String zeroSubject = signedToken(claims -> claims.subject("0").issuedAt(now).expiresAt(now.plusSeconds(60)));

        for (String token : List.of(noSubject, textSubject, zeroSubject)) {
            for (String path : List.of("/api/home", "/api/users/me")) {
                mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
            }
        }
    }

    @Test
    @DisplayName("JWT 형식이 아닌 값은 401 INVALID_TOKEN")
    void malformedTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    /** 서버 키로 서명한 Access Token. 클레임 구성만 호출부에서 정한다. (비정상 클레임 검증용) */
    private String signedToken(Consumer<JwtClaimsSet.Builder> customizer) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .claim("token_type", "access")
                .claim("role", "OWNER");
        customizer.accept(claims);
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableSecret<>(new SecretKeySpec(jwtProperties.secretBytes(), "HmacSHA256")));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }
}
