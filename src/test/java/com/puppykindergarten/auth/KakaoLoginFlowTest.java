package com.puppykindergarten.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.puppykindergarten.auth.jwt.JwtTokenProvider;
import com.puppykindergarten.auth.token.AuthTokenService;
import com.puppykindergarten.auth.token.RefreshToken;
import com.puppykindergarten.auth.token.RefreshTokenRepository;
import com.puppykindergarten.auth.token.TokenHasher;
import com.puppykindergarten.user.Role;
import com.puppykindergarten.user.User;
import com.puppykindergarten.user.UserRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 카카오 로그인 전체 흐름 테스트.
 * 인가 요청 리다이렉트 → 콜백(code, state) → 토큰 교환 → 사용자 조회 → 가입/로그인 까지를
 * 가짜 카카오 서버를 상대로 실제 시큐리티 필터 체인으로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KakaoLoginFlowTest {

    private static final FakeKakaoServer KAKAO = new FakeKakaoServer();

    @DynamicPropertySource
    static void kakaoEndpoints(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.kakao.token-uri", () -> KAKAO.baseUrl() + "/oauth/token");
        registry.add("spring.security.oauth2.client.provider.kakao.user-info-uri", () -> KAKAO.baseUrl() + "/v2/user/me");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    JwtTokenProvider jwtTokenProvider;

    @Autowired
    ObjectMapper objectMapper;

    @SpyBean
    AuthTokenService authTokenService;

    @SpyBean
    KakaoLoginService kakaoLoginService;

    @BeforeEach
    void setUp() {
        KAKAO.reset();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("로그인 시작 시 카카오 인가 페이지로 리다이렉트한다")
    void redirectsToKakaoAuthorizePage() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/kakao"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        Map<String, String> params = UriComponentsBuilder.fromUriString(location).build().getQueryParams().toSingleValueMap();

        assertThat(location).startsWith("https://kauth.kakao.com/oauth/authorize?");
        assertThat(params).containsEntry("response_type", "code");
        assertThat(params).containsEntry("client_id", "test-client-id");
        assertThat(params).containsEntry("scope", "profile_nickname");
        assertThat(params.get("redirect_uri")).isEqualTo("http://localhost/login/oauth2/code/kakao");
        assertThat(params.get("state")).isNotBlank();
        // client secret 은 브라우저를 거치는 인가 요청에 실리면 안 된다.
        assertThat(location).doesNotContain("test-client-secret");
    }

    @Test
    @DisplayName("최초 로그인: 토큰 교환 후 견주로 가입되고 사용자 정보가 응답된다")
    void firstLoginRegistersOwner() throws Exception {
        KAKAO.respondWithUser(1001L, "초코아빠");

        LoginAttempt attempt = startLogin();
        callback(attempt, "auth-code-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.nickname").value("초코아빠"))
                .andExpect(jsonPath("$.role").value("OWNER"));

        User saved = userRepository.findByKakaoId(1001L).orElseThrow();
        assertThat(saved.getRole()).isEqualTo(Role.OWNER);

        // 토큰 요청: authorization_code 방식, client_secret_post
        assertThat(KAKAO.tokenRequestBodies()).hasSize(1);
        String tokenRequest = KAKAO.tokenRequestBodies().get(0);
        assertThat(tokenRequest)
                .contains("grant_type=authorization_code")
                .contains("code=auth-code-1")
                .contains("client_id=test-client-id")
                .contains("client_secret=test-client-secret");
        // 사용자 정보 요청: 카카오 access token 을 Bearer 로 전달
        assertThat(KAKAO.userInfoAuthorizationHeaders()).containsExactly("Bearer " + FakeKakaoServer.ACCESS_TOKEN);
        // 로그인에 쓰인 세션은 폐기된다.
        assertThat(attempt.session().isInvalid()).isTrue();
    }

    @Test
    @DisplayName("재로그인: 같은 카카오 계정은 중복 가입되지 않는다")
    void secondLoginDoesNotDuplicateUser() throws Exception {
        KAKAO.respondWithUser(1001L, "초코아빠");

        callback(startLogin(), "code-a").andExpect(status().isOk());
        callback(startLogin(), "code-b").andExpect(status().isOk());

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("사전 지정된 카카오 회원번호로 로그인하면 원장 역할이 된다")
    void preassignedKakaoIdBecomesDirector() throws Exception {
        KAKAO.respondWithUser(9001L, "해피원장");

        callback(startLogin(), "code-director")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("DIRECTOR"));
    }

    @Test
    @DisplayName("state 가 일치하지 않는 콜백은 거부하고 카카오 토큰도 요청하지 않는다 (CSRF 방어)")
    void rejectsCallbackWithWrongState() throws Exception {
        KAKAO.respondWithUser(1001L, "초코아빠");
        LoginAttempt attempt = startLogin();

        mockMvc.perform(get("/login/oauth2/code/kakao")
                        .session(attempt.session())
                        .param("code", "stolen-code")
                        .param("state", "forged-state"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("KAKAO_LOGIN_FAILED"));

        assertThat(KAKAO.tokenRequestBodies()).isEmpty();
        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("로그인을 시작한 적 없는 콜백(세션 없음)은 거부한다")
    void rejectsCallbackWithoutLoginStart() throws Exception {
        mockMvc.perform(get("/login/oauth2/code/kakao")
                        .param("code", "any-code")
                        .param("state", "any-state"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("KAKAO_LOGIN_FAILED"));

        assertThat(KAKAO.tokenRequestBodies()).isEmpty();
    }

    @Test
    @DisplayName("카카오가 인가 코드를 거부하면 401 을 응답하고 가입시키지 않는다")
    void failsWhenKakaoRejectsCode() throws Exception {
        KAKAO.failTokenEndpoint();

        callback(startLogin(), "expired-code")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("KAKAO_LOGIN_FAILED"))
                .andExpect(jsonPath("$.message").value("카카오 로그인에 실패했습니다."));

        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("인증 없이 보호된 API 를 호출하면 리다이렉트가 아니라 401 JSON 을 응답한다")
    void unauthenticatedRequestGets401() throws Exception {
        mockMvc.perform(get("/api/home"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("로그인에 성공해도 세션으로는 보호된 API 에 접근할 수 없다")
    void sessionDoesNotAuthenticateAfterLogin() throws Exception {
        KAKAO.respondWithUser(1001L, "초코아빠");
        LoginAttempt attempt = startLogin();
        MvcResult login = callback(attempt, "code-1").andExpect(status().isOk()).andReturn();

        MockHttpSession after = (MockHttpSession) login.getRequest().getSession(false);
        var request = get("/api/home");
        if (after != null && !after.isInvalid()) {
            request = request.session(after);
        }
        mockMvc.perform(request).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그인 성공 시 자체 JWT(Access/Refresh)를 발급하고, Access Token 으로 API 를 호출할 수 있다")
    void loginIssuesUsableTokens() throws Exception {
        KAKAO.respondWithUser(9001L, "해피원장");

        MvcResult login = callback(startLogin(), "code-1")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(900))
                .andReturn();
        JsonNode body = objectMapper.readTree(login.getResponse().getContentAsString());
        String accessToken = body.get("accessToken").asText();
        String refreshToken = body.get("refreshToken").asText();

        // Access Token 클레임: 사용자 식별자 + 역할
        Jwt jwt = jwtTokenProvider.accessTokenDecoder().decode(accessToken);
        assertThat(jwt.getSubject()).isEqualTo(body.get("userId").asText());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("DIRECTOR");

        // 발급받은 Access Token 으로 보호된 API 호출
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("해피원장"))
                .andExpect(jsonPath("$.role").value("DIRECTOR"));

        // Refresh Token 은 원문이 아니라 해시로만 저장된다.
        List<RefreshToken> stored = refreshTokenRepository.findAll();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getTokenHash())
                .isEqualTo(TokenHasher.sha256Hex(refreshToken))
                .isNotEqualTo(refreshToken);
        assertThat(stored.get(0).getUserId()).isEqualTo(body.get("userId").asLong());
    }

    @Test
    @DisplayName("전체 흐름: 카카오 로그인 → JWT 발급 → 같은 홈 API 가 역할에 따라 다르게 응답한다")
    void loginThenRoleBasedHome() throws Exception {
        KAKAO.respondWithUser(1001L, "초코아빠");
        String ownerToken = objectMapper.readTree(callback(startLogin(), "code-owner")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();

        KAKAO.respondWithUser(9001L, "해피원장");
        String directorToken = objectMapper.readTree(callback(startLogin(), "code-director")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/api/home").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.myDogs").isArray())
                .andExpect(jsonPath("$.enrolledDogs").doesNotExist());
        mockMvc.perform(get("/api/home").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("DIRECTOR"))
                .andExpect(jsonPath("$.todayAttendance").value(5))
                .andExpect(jsonPath("$.myDogs").doesNotExist());
    }

    @Test
    @DisplayName("카카오 토큰 교환이 제한 시간 안에 응답하지 않으면 기다리지 않고 503 을 응답한다")
    void tokenEndpointTimeoutReturns503() throws Exception {
        KAKAO.respondWithUser(1001L, "초코아빠");
        KAKAO.delayTokenEndpoint(4000); // 테스트 설정의 응답 제한 시간은 1초

        long startedAt = System.nanoTime();
        callback(startLogin(), "code-slow")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KAKAO_UNAVAILABLE"));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(3000);
        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("카카오 사용자 정보 조회가 제한 시간 안에 응답하지 않으면 503 을 응답하고 가입시키지 않는다")
    void userInfoEndpointTimeoutReturns503() throws Exception {
        KAKAO.respondWithUser(1001L, "초코아빠");
        KAKAO.delayUserInfoEndpoint(4000);

        long startedAt = System.nanoTime();
        callback(startLogin(), "code-slow")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KAKAO_UNAVAILABLE"));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(3000);
        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("토큰 저장 중 DB 오류가 나면 401 이 아닌 500 을 공통 에러 형식으로 응답한다")
    void tokenIssueFailureReturns500InCommonFormat() throws Exception {
        KAKAO.respondWithUser(1001L, "초코아빠");
        doThrow(new DataAccessResourceFailureException("DB down"))
                .when(authTokenService).issue(any(), any());

        callback(startLogin(), "code-1")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    @DisplayName("회원 저장 중 DB 오류가 나면 로그인 실패(401)와 구분해 500 을 응답한다")
    void userSaveFailureReturns500InCommonFormat() throws Exception {
        KAKAO.respondWithUser(1001L, "초코아빠");
        doThrow(new DataAccessResourceFailureException("DB down"))
                .when(kakaoLoginService).loginOrRegister(any(), any());

        callback(startLogin(), "code-1")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    @DisplayName("로그인 중 DB 연결 자체가 안 되면(트랜잭션 시작 실패) 500 을 공통 에러 형식으로 응답한다")
    void dbConnectionFailureDuringLoginReturns500() throws Exception {
        KAKAO.respondWithUser(1001L, "초코아빠");
        doThrow(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"))
                .when(kakaoLoginService).loginOrRegister(any(), any());

        callback(startLogin(), "code-1")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    private record LoginAttempt(MockHttpSession session, String state) {
    }

    private LoginAttempt startLogin() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/kakao"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String state = UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl())
                .build().getQueryParams().getFirst("state");
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        return new LoginAttempt(session, java.net.URLDecoder.decode(state, java.nio.charset.StandardCharsets.UTF_8));
    }

    private ResultActions callback(LoginAttempt attempt, String code) throws Exception {
        return mockMvc.perform(get("/login/oauth2/code/kakao")
                .session(attempt.session())
                .param("code", code)
                .param("state", attempt.state()));
    }
}
