package com.puppykindergarten.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.puppykindergarten.user.Role;
import com.puppykindergarten.user.User;
import com.puppykindergarten.user.UserRepository;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class KakaoLoginServiceTest {

    private static final Long OWNER_KAKAO_ID = 1001L;
    private static final Long DIRECTOR_KAKAO_ID = 9001L; // 테스트 설정(app.auth.director-kakao-ids)에 등록된 값

    @Autowired
    KakaoLoginService kakaoLoginService;

    @Autowired
    UserRepository userRepository;

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
        KakaoLoginService before = new KakaoLoginService(userRepository, new AuthProperties(Set.of()), clock);
        KakaoLoginService promoted = new KakaoLoginService(userRepository, new AuthProperties(Set.of(OWNER_KAKAO_ID)), clock);

        assertThat(before.loginOrRegister(OWNER_KAKAO_ID, "초코아빠").getRole()).isEqualTo(Role.OWNER);
        assertThat(promoted.loginOrRegister(OWNER_KAKAO_ID, "초코아빠").getRole()).isEqualTo(Role.DIRECTOR);
        assertThat(before.loginOrRegister(OWNER_KAKAO_ID, "초코아빠").getRole()).isEqualTo(Role.OWNER);
        assertThat(userRepository.count()).isEqualTo(1);
    }
}
