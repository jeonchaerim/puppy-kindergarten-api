package com.puppykindergarten.auth;

import com.puppykindergarten.user.Role;
import com.puppykindergarten.user.User;
import com.puppykindergarten.user.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카카오 인증이 끝난 사용자를 우리 서비스 회원으로 연결한다.
 * 최초 로그인이면 가입시키고, 이후 로그인이면 프로필/역할을 동기화한다.
 */
@Service
public class KakaoLoginService {

    private final UserRepository userRepository;
    private final AuthProperties authProperties;
    private final Clock clock;

    public KakaoLoginService(UserRepository userRepository, AuthProperties authProperties, Clock clock) {
        this.userRepository = userRepository;
        this.authProperties = authProperties;
        this.clock = clock;
    }

    @Transactional
    public User loginOrRegister(Long kakaoId, String nickname) {
        Role role = resolveRole(kakaoId);
        LocalDateTime now = LocalDateTime.now(clock);

        return userRepository.findByKakaoId(kakaoId)
                .map(user -> {
                    user.recordLogin(nickname, role, now);
                    return user;
                })
                .orElseGet(() -> userRepository.save(User.register(kakaoId, nickname, role, now)));
    }

    /**
     * 역할은 사용자가 고를 수 없다. 서버에 사전 지정된 카카오 회원번호만 원장이 되고,
     * 그 외에는 모두 견주다. 로그인할 때마다 다시 계산하므로 목록 변경이 다음 로그인에 반영된다.
     */
    private Role resolveRole(Long kakaoId) {
        return authProperties.isDirector(kakaoId) ? Role.DIRECTOR : Role.OWNER;
    }
}
