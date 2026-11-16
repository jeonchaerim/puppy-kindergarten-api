package com.puppykindergarten.auth;

import com.puppykindergarten.user.Role;
import com.puppykindergarten.user.User;
import com.puppykindergarten.user.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 카카오 인증이 끝난 사용자를 가입시키거나 기존 회원의 프로필/역할을 동기화한다.
 *
 * 같은 카카오 계정의 최초 로그인이 동시에 들어오면 둘 다 "회원 없음" 으로 조회한 뒤 INSERT 를 시도할 수 있다.
 * users.kakao_id 유일 제약으로 한쪽 INSERT 가 실패하면, 실패한 트랜잭션은 버리고 새 트랜잭션에서
 * 먼저 가입된 회원을 다시 조회해 로그인시킨다. 재시도가 항상 새 트랜잭션에서 돌도록 REQUIRES_NEW 를 쓴다.
 */
@Service
public class KakaoLoginService {

    private final UserRepository userRepository;
    private final AuthProperties authProperties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public KakaoLoginService(UserRepository userRepository, AuthProperties authProperties, Clock clock,
                             PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.authProperties = authProperties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public User loginOrRegister(Long kakaoId, String nickname) {
        Role role = authProperties.resolveRole(kakaoId);
        try {
            return transaction.execute(status -> userRepository.findByKakaoId(kakaoId)
                    .map(user -> recordLogin(user, nickname, role))
                    .orElseGet(() -> userRepository.save(
                            User.register(kakaoId, nickname, role, LocalDateTime.now(clock)))));
        } catch (DataIntegrityViolationException conflict) {
            // 동시 최초 가입의 패배한 INSERT는 먼저 롤백한다.
            // 새 트랜잭션에서 승자가 저장한 회원을 조회한다. 실패한 트랜잭션은 재사용하지 않는다.
            return transaction.execute(status -> userRepository.findByKakaoId(kakaoId)
                    .map(user -> recordLogin(user, nickname, role))
                    // 중복 가입으로 설명되지 않는 무결성 오류는 숨기지 않는다.
                    .orElseThrow(() -> conflict));
        }
    }

    private User recordLogin(User user, String nickname, Role role) {
        user.recordLogin(nickname, role, LocalDateTime.now(clock));
        return user;
    }
}
