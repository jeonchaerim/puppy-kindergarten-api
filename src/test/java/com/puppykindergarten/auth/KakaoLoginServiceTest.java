package com.puppykindergarten.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.puppykindergarten.auth.token.RefreshTokenRepository;
import com.puppykindergarten.user.Role;
import com.puppykindergarten.user.User;
import com.puppykindergarten.user.UserRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
class KakaoLoginServiceTest {

    private static final Long OWNER_KAKAO_ID = 1001L;
    private static final Long DIRECTOR_KAKAO_ID = 9001L; // 테스트 설정(app.auth.director-kakao-ids)에 등록된 값

    @Autowired
    KakaoLoginService kakaoLoginService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("최초 로그인이면 견주(OWNER)로 가입된다")
    void registersAsOwnerOnFirstLogin() {
        User user = kakaoLoginService.loginOrRegister(OWNER_KAKAO_ID, "초코아빠");

        assertThat(user.getId()).isNotNull();
        assertThat(user.getKakaoId()).isEqualTo(OWNER_KAKAO_ID);
        assertThat(user.getNickname()).isEqualTo("초코아빠");
        assertThat(user.getRole()).isEqualTo(Role.OWNER);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("다시 로그인하면 새로 가입하지 않고 기존 회원을 쓰며 닉네임을 갱신한다")
    void reusesExistingUserOnNextLogin() {
        User first = kakaoLoginService.loginOrRegister(OWNER_KAKAO_ID, "초코아빠");
        User second = kakaoLoginService.loginOrRegister(OWNER_KAKAO_ID, "초코엄마");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getNickname()).isEqualTo("초코엄마");
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("사전 지정된 카카오 회원번호는 원장(DIRECTOR)으로 가입된다")
    void registersPreassignedKakaoIdAsDirector() {
        User user = kakaoLoginService.loginOrRegister(DIRECTOR_KAKAO_ID, "해피원장");

        assertThat(user.getRole()).isEqualTo(Role.DIRECTOR);
    }

    @Test
    @DisplayName("원장 목록이 바뀌면 다음 로그인 때 역할이 동기화된다")
    void syncsRoleOnEveryLogin() {
        Clock clock = Clock.systemDefaultZone();
        KakaoLoginService before = new KakaoLoginService(userRepository, new AuthProperties(Set.of()), clock, transactionManager);
        KakaoLoginService promoted = new KakaoLoginService(userRepository, new AuthProperties(Set.of(OWNER_KAKAO_ID)), clock, transactionManager);

        assertThat(before.loginOrRegister(OWNER_KAKAO_ID, "초코아빠").getRole()).isEqualTo(Role.OWNER);
        assertThat(promoted.loginOrRegister(OWNER_KAKAO_ID, "초코아빠").getRole()).isEqualTo(Role.DIRECTOR);
        assertThat(before.loginOrRegister(OWNER_KAKAO_ID, "초코아빠").getRole()).isEqualTo(Role.OWNER);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 최초 로그인: 두 요청 모두 같은 회원으로 성공하고 프로필/역할이 저장된다")
    void concurrentFirstLoginsReuseTheSameUser() throws Exception {
        CyclicBarrier bothReadMissing = new CyclicBarrier(2);
        // 실제 DB 조회 결과가 둘 다 '없음'일 때만 대기해 INSERT 충돌을 확실하게 재현한다.
        UserRepository synchronizedReads = (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(), new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(userRepository, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (method.getName().equals("findByKakaoId") && ((Optional<?>) result).isEmpty()) {
                        bothReadMissing.await(10, TimeUnit.SECONDS);
                    }
                    return result;
                });
        KakaoLoginService service = new KakaoLoginService(
                synchronizedReads, new AuthProperties(Set.of(DIRECTOR_KAKAO_ID)), Clock.systemUTC(), transactionManager);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.loginOrRegister(DIRECTOR_KAKAO_ID, "첫번째"));
            var second = pool.submit(() -> service.loginOrRegister(DIRECTOR_KAKAO_ID, "두번째"));
            User a = first.get(20, TimeUnit.SECONDS);
            User b = second.get(20, TimeUnit.SECONDS);

            assertThat(a.getId()).isEqualTo(b.getId());
            assertThat(a.getNickname()).isEqualTo("첫번째");
            assertThat(b.getNickname()).isEqualTo("두번째");
            assertThat(a.getRole()).isEqualTo(Role.DIRECTOR);
            assertThat(b.getRole()).isEqualTo(Role.DIRECTOR);
            assertThat(userRepository.count()).isEqualTo(1);
            User saved = userRepository.findById(a.getId()).orElseThrow();
            assertThat(saved.getRole()).isEqualTo(Role.DIRECTOR);
            assertThat(saved.getNickname()).isIn("첫번째", "두번째");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("중복 가입이 아닌 무결성 오류는 숨기지 않고 가입을 롤백한다")
    void unrelatedIntegrityViolationIsNotSwallowed() {
        assertThatThrownBy(() -> kakaoLoginService.loginOrRegister(OWNER_KAKAO_ID, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(userRepository.count()).isZero();
    }
}
