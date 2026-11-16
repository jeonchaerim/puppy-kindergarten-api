package com.puppykindergarten.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.puppykindergarten.auth.jwt.JwtProperties;
import com.puppykindergarten.auth.jwt.JwtTokenProvider;
import com.puppykindergarten.auth.token.RefreshTokenRepository;
import com.puppykindergarten.user.Role;
import com.puppykindergarten.user.User;
import com.puppykindergarten.user.UserRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
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
 * 같은 GET /api/home 이 JWT 의 role 에 따라 다른 응답을 주는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HomeApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtTokenProvider jwtTokenProvider;

    @Autowired
    JwtProperties jwtProperties;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    Long ownerId;
    Long directorId;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        ownerId = userRepository.save(User.register(1001L, "초코아빠", Role.OWNER, LocalDateTime.now())).getId();
        directorId = userRepository.save(User.register(9001L, "해피원장", Role.DIRECTOR, LocalDateTime.now())).getId();
    }

    @Test
    @DisplayName("견주 토큰: 내 반려견과 주변 유치원을 응답한다")
    void ownerHome() throws Exception {
        String token = jwtTokenProvider.createAccessToken(ownerId, Role.OWNER);

        mockMvc.perform(get("/api/home").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.myDogs").isArray())
                .andExpect(jsonPath("$.myDogs[0].name").value("초코"))
                .andExpect(jsonPath("$.nearbyKindergartens").isArray())
                .andExpect(jsonPath("$.nearbyKindergartens[0].name").isNotEmpty())
                // 원장 전용 정보는 견주에게 나가지 않는다.
                .andExpect(jsonPath("$.kindergartenName").doesNotExist())
                .andExpect(jsonPath("$.enrolledDogs").doesNotExist())
                .andExpect(jsonPath("$.todayAttendance").doesNotExist());
    }

    @Test
    @DisplayName("원장 토큰: 유치원 이름, 등록된 원생, 오늘 등원 수를 응답한다")
    void directorHome() throws Exception {
        String token = jwtTokenProvider.createAccessToken(directorId, Role.DIRECTOR);

        mockMvc.perform(get("/api/home").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("DIRECTOR"))
                .andExpect(jsonPath("$.kindergartenName").value("해피퍼피 유치원"))
                .andExpect(jsonPath("$.enrolledDogs.length()").value(6))
                .andExpect(jsonPath("$.todayAttendance").value(5))
                // 견주 전용 정보는 원장에게 나가지 않는다.
                .andExpect(jsonPath("$.myDogs").doesNotExist())
                .andExpect(jsonPath("$.nearbyKindergartens").doesNotExist());
    }

    @Test
    @DisplayName("같은 사용자 ID 라도 토큰의 role 이 다르면 응답이 달라진다")
    void responseFollowsRoleClaimNotUserId() throws Exception {
        String asOwner = jwtTokenProvider.createAccessToken(ownerId, Role.OWNER);
        String asDirector = jwtTokenProvider.createAccessToken(ownerId, Role.DIRECTOR);

        mockMvc.perform(get("/api/home").header(HttpHeaders.AUTHORIZATION, "Bearer " + asOwner))
                .andExpect(jsonPath("$.role").value("OWNER"));
        mockMvc.perform(get("/api/home").header(HttpHeaders.AUTHORIZATION, "Bearer " + asDirector))
                .andExpect(jsonPath("$.role").value("DIRECTOR"));
    }

    @Test
    @DisplayName("역할은 요청 파라미터로 바꿀 수 없다")
    void roleCannotBeOverriddenByRequest() throws Exception {
        String token = jwtTokenProvider.createAccessToken(ownerId, Role.OWNER);

        mockMvc.perform(get("/api/home").param("role", "DIRECTOR")
                        .header("X-Role", "DIRECTOR")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.enrolledDogs").doesNotExist());
    }

    @Test
    @DisplayName("토큰은 유효하지만 회원이 없으면(탈퇴 등) 404")
    void deletedUserGetsNotFound() throws Exception {
        String token = jwtTokenProvider.createAccessToken(ownerId, Role.OWNER);
        userRepository.deleteById(ownerId);

        mockMvc.perform(get("/api/home").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("토큰이 없으면 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(get("/api/home"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("서명은 유효하지만 역할이 없거나 알 수 없는 토큰은 403")
    void unknownOrMissingRoleIsForbidden() throws Exception {
        mockMvc.perform(get("/api/home").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenWithRole("ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/home").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenWithRole(null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("모든 역할에 홈 구현이 있어야 하고, 없으면 구성 단계에서 실패한다")
    void everyRoleNeedsProvider() {
        assertThatThrownBy(() -> new HomeService(List.of(new OwnerHomeProvider())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HomeService(
                List.of(new OwnerHomeProvider(), new OwnerHomeProvider(), new DirectorHomeProvider())))
                .isInstanceOf(IllegalStateException.class);

        HomeService service = new HomeService(List.of(new OwnerHomeProvider(), new DirectorHomeProvider()));
        assertThat(service.getHome(1L, Role.OWNER)).isInstanceOf(HomeResponse.OwnerHome.class);
        assertThat(service.getHome(1L, Role.DIRECTOR)).isInstanceOf(HomeResponse.DirectorHome.class);
    }

    /** 서버 키로 서명했지만 role 클레임이 비정상인 Access Token. */
    private String accessTokenWithRole(String role) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject("1")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .claim("token_type", "access");
        if (role != null) {
            claims.claim("role", role);
        }
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableSecret<>(new SecretKeySpec(jwtProperties.secretBytes(), "HmacSHA256")));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }
}
