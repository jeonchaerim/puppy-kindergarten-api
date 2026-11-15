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
        Role role = authProperties.resolveRole(kakaoId);
        LocalDateTime now = LocalDateTime.now(clock);

        return userRepository.findByKakaoId(kakaoId)
                .map(user -> {
                    user.recordLogin(nickname, role, now);
                    return user;
                })
                .orElseGet(() -> userRepository.save(User.register(kakaoId, nickname, role, now)));
    }
}
