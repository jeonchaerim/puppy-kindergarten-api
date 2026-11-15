package com.puppykindergarten.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.puppykindergarten.auth.jwt.JwtProperties;
import com.puppykindergarten.auth.jwt.JwtTokenProvider;
import com.puppykindergarten.auth.token.AuthTokenService;
import com.puppykindergarten.auth.token.RefreshTokenRepository;
import com.puppykindergarten.auth.token.TokenHasher;
import com.puppykindergarten.auth.token.TokenPair;
import com.puppykindergarten.user.Role;
import com.puppykindergarten.user.User;
import com.puppykindergarten.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class TokenReissueLogoutTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AuthTokenService authTokenService;

    @Autowired
    JwtTokenProvider jwtTokenProvider;

    @Autowired
    JwtProperties jwtProperties;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    UserRepository userRepository;

    User owner;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        owner = userRepository.save(User.register(1001L, "초코아빠", Role.OWNER, java.time.LocalDateTime.now()));
    }

    @Test
    @DisplayName("재발급: 새 토큰 쌍을 받고, 새 Access Token 으로 API 를 호출할 수 있다")
    void reissueReturnsNewUsableTokens() throws Exception {
        TokenPair issued = authTokenService.issue(owner.getId(), owner.getRole());

        JsonNode body = json(reissue(issued.refreshToken())
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(900)));
        String newAccess = body.get("accessToken").asText();
        String newRefresh = body.get("refreshToken").asText();

        assertThat(newRefresh).isNotEqualTo(issued.refreshToken());
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(owner.getId()));
        // 저장소에는 새 Refresh Token 의 해시 하나만 남는다. (교체)
        assertThat(refreshTokenRepository.findAll())
                .extracting("tokenHash")
                .containsExactly(TokenHasher.sha256Hex(newRefresh));
    }

    @Test
    @DisplayName("재발급에 사용한 Refresh Token 은 다시 쓸 수 없다 (1회용)")
    void usedRefreshTokenCannotBeReused() throws Exception {
        TokenPair issued = authTokenService.issue(owner.getId(), owner.getRole());
        reissue(issued.refreshToken()).andExpect(status().isOk());

        reissue(issued.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    @DisplayName("이미 사용한 Refresh Token 을 다시 제시해도 교체로 받은 새 토큰은 계속 유효하다")
    void reusedTokenDoesNotRevokeOthers() throws Exception {
        TokenPair issued = authTokenService.issue(owner.getId(), owner.getRole());
        String rotated = json(reissue(issued.refreshToken()).andExpect(status().isOk())).get("refreshToken").asText();

        reissue(issued.refreshToken()).andExpect(status().isUnauthorized());

        reissue(rotated).andExpect(status().isOk());
    }

    @Test
    @DisplayName("재발급 시 역할을 다시 계산한다: 원장 목록에서 빠진 사용자는 견주 토큰을 받는다")
    void reissueDemotesUserRemovedFromDirectorList() throws Exception {
        // DB 에는 원장으로 남아 있지만, 카카오 ID 1001 은 원장 목록(9001, 9002)에 없다.
        owner.syncRole(Role.DIRECTOR);
        userRepository.save(owner);
        TokenPair issued = authTokenService.issue(owner.getId(), Role.DIRECTOR);

        String newAccess = json(reissue(issued.refreshToken()).andExpect(status().isOk())).get("accessToken").asText();

        assertThat(jwtTokenProvider.accessTokenDecoder().decode(newAccess).getClaimAsString("role")).isEqualTo("OWNER");
        assertThat(userRepository.findById(owner.getId()).orElseThrow().getRole()).isEqualTo(Role.OWNER);
    }

    @Test
    @DisplayName("재발급 시 역할을 다시 계산한다: 원장 목록에 추가된 사용자는 원장 토큰을 받는다")
    void reissuePromotesUserAddedToDirectorList() throws Exception {
        // 카카오 ID 9001 은 원장 목록에 있지만, DB 에는 아직 견주로 남아 있다.
        User director = userRepository.save(User.register(9001L, "해피원장", Role.OWNER, java.time.LocalDateTime.now()));
        TokenPair issued = authTokenService.issue(director.getId(), Role.OWNER);

        String newAccess = json(reissue(issued.refreshToken()).andExpect(status().isOk())).get("accessToken").asText();

        assertThat(jwtTokenProvider.accessTokenDecoder().decode(newAccess).getClaimAsString("role")).isEqualTo("DIRECTOR");
        assertThat(userRepository.findById(director.getId()).orElseThrow().getRole()).isEqualTo(Role.DIRECTOR);
    }

    @Test
    @DisplayName("만료된 Access Token 이 Authorization 헤더에 붙어 있어도 재발급·로그아웃할 수 있다")
    void expiredBearerHeaderDoesNotBlockReissueOrLogout() throws Exception {
        TokenPair issued = authTokenService.issue(owner.getId(), owner.getRole());
        Clock oneHourAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        String expiredAccess = new JwtTokenProvider(jwtProperties, oneHourAgo).createAccessToken(owner.getId(), Role.OWNER);

        // 같은 헤더로 일반 API 는 거부된다.
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + expiredAccess))
                .andExpect(status().isUnauthorized());

        String rotated = json(mockMvc.perform(post("/api/auth/reissue")
                        .header("Authorization", "Bearer " + expiredAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", issued.refreshToken()))))
                .andExpect(status().isOk())).get("refreshToken").asText();

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + expiredAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", rotated))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("만료된 Refresh Token 으로는 재발급할 수 없다")
    void expiredRefreshTokenIsRejected() throws Exception {
        Clock longAgo = Clock.fixed(Instant.now().minus(Duration.ofDays(30)), ZoneOffset.UTC);
        String expired = new JwtTokenProvider(jwtProperties, longAgo).createRefreshToken(owner.getId()).value();

        reissue(expired)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    @DisplayName("Access Token 이나 임의의 문자열로는 재발급할 수 없다")
    void nonRefreshTokenIsRejected() throws Exception {
        TokenPair issued = authTokenService.issue(owner.getId(), owner.getRole());

        reissue(issued.accessToken()).andExpect(status().isUnauthorized());
        reissue("not-a-jwt").andExpect(status().isUnauthorized());
        // 실패한 시도는 정상 토큰에 영향을 주지 않는다.
        assertThat(refreshTokenRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("요청 본문이 없거나 비어 있으면 400")
    void blankRequestIsBadRequest() throws Exception {
        reissue(" ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/api/auth/reissue").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("로그아웃: Refresh Token 이 폐기되어 더는 재발급할 수 없다")
    void logoutRevokesRefreshToken() throws Exception {
        TokenPair issued = authTokenService.issue(owner.getId(), owner.getRole());

        logout(issued.refreshToken()).andExpect(status().isNoContent());

        assertThat(refreshTokenRepository.count()).isZero();
        reissue(issued.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    @DisplayName("로그아웃 정책: 이미 발급된 Access Token 은 만료 시각까지 유효하다")
    void accessTokenStaysValidUntilExpiryAfterLogout() throws Exception {
        TokenPair issued = authTokenService.issue(owner.getId(), owner.getRole());

        logout(issued.refreshToken()).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + issued.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("로그아웃은 Access Token 없이 호출할 수 있고, 여러 번 호출해도 성공한다 (멱등)")
    void logoutIsIdempotent() throws Exception {
        TokenPair issued = authTokenService.issue(owner.getId(), owner.getRole());

        logout(issued.refreshToken()).andExpect(status().isNoContent());
        logout(issued.refreshToken()).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("로그아웃은 제시한 토큰만 폐기한다 (다른 기기·다른 사용자의 토큰은 유지)")
    void logoutRevokesOnlyPresentedToken() throws Exception {
        User other = userRepository.save(User.register(1002L, "보리엄마", Role.OWNER, java.time.LocalDateTime.now()));
        TokenPair phone = authTokenService.issue(owner.getId(), owner.getRole());
        TokenPair tablet = authTokenService.issue(owner.getId(), owner.getRole());
        TokenPair othersToken = authTokenService.issue(other.getId(), other.getRole());

        logout(phone.refreshToken()).andExpect(status().isNoContent());

        reissue(tablet.refreshToken()).andExpect(status().isOk());
        reissue(othersToken.refreshToken()).andExpect(status().isOk());
    }

    private ResultActions reissue(String refreshToken) throws Exception {
        return postJson("/api/auth/reissue", refreshToken);
    }

    private ResultActions logout(String refreshToken) throws Exception {
        return postJson("/api/auth/logout", refreshToken);
    }

    private ResultActions postJson(String path, String refreshToken) throws Exception {
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))));
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
