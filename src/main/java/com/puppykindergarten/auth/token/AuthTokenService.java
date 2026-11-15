package com.puppykindergarten.auth.token;

import com.puppykindergarten.auth.AuthProperties;
import com.puppykindergarten.auth.jwt.JwtTokenProvider;
import com.puppykindergarten.auth.jwt.JwtTokenProvider.IssuedRefreshToken;
import com.puppykindergarten.user.Role;
import com.puppykindergarten.user.User;
import com.puppykindergarten.user.UserRepository;
import java.time.Clock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Access/Refresh Token 의 발급, 재발급, 폐기.
 *
 * <ul>
 *   <li>발급: Refresh Token 은 해시로 저장해 둔다.</li>
 *   <li>재발급: 제시된 Refresh Token 을 폐기하고 새 쌍으로 교체한다. (1회용)</li>
 *   <li>로그아웃: 제시된 Refresh Token 을 폐기한다. Access Token 은 만료 시각까지 유효하다.</li>
 * </ul>
 *
 * 같은 토큰으로 재발급·로그아웃이 동시에 오면 MySQL(InnoDB)은 행 잠금 순서 때문에 교착 상태(deadlock)를 내고
 * 한쪽 트랜잭션을 강제로 롤백한다. 이때는 새 트랜잭션에서 다시 시도한다. 다시 시도할 때 토큰이 이미
 * 폐기돼 있으면 정상적으로 401 이 된다.
 */
@Service
public class AuthTokenService {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final AuthProperties authProperties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public AuthTokenService(JwtTokenProvider jwtTokenProvider, RefreshTokenRepository refreshTokenRepository,
                            UserRepository userRepository, AuthProperties authProperties, Clock clock,
                            PlatformTransactionManager transactionManager) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.authProperties = authProperties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public TokenPair issue(Long userId, Role role) {
        String accessToken = jwtTokenProvider.createAccessToken(userId, role);
        IssuedRefreshToken refreshToken = jwtTokenProvider.createRefreshToken(userId);

        refreshTokenRepository.save(new RefreshToken(
                userId, TokenHasher.sha256Hex(refreshToken.value()), refreshToken.expiresAt(), clock.instant()));

        return TokenPair.bearer(accessToken, jwtTokenProvider.accessTtl().toSeconds(), refreshToken.value());
    }

    /** Refresh Token 으로 새 토큰 쌍을 발급한다. 사용한 Refresh Token 은 즉시 폐기된다. */
    public TokenPair reissue(String refreshToken) {
        Jwt jwt = decode(refreshToken);
        Long userId = JwtTokenProvider.userIdOf(jwt);
        String tokenHash = TokenHasher.sha256Hex(refreshToken);

        return retryOnDeadlock("재발급", () -> {
            // 저장소에 없으면 이미 교체됐거나 로그아웃된 토큰이다.
            int revoked = refreshTokenRepository.deleteByTokenHash(tokenHash);
            if (revoked == 0) {
                throw new InvalidRefreshTokenException();
            }

            User user = userRepository.findById(userId).orElseThrow(InvalidRefreshTokenException::new);
            // 로그인 때와 같은 기준으로 역할을 다시 계산한다.
            // (원장 목록에서 빠진 사용자가 재발급만으로 원장 권한을 유지하지 못하게 한다)
            Role role = authProperties.resolveRole(user.getKakaoId());
            user.syncRole(role);
            return issue(user.getId(), role);
        });
    }

    /** 로그아웃. 이미 폐기된 토큰이어도 조용히 성공한다. (멱등) */
    public void logout(String refreshToken) {
        String tokenHash = TokenHasher.sha256Hex(refreshToken);
        retryOnDeadlock("로그아웃", () -> refreshTokenRepository.deleteByTokenHash(tokenHash));
    }

    /**
     * 작업을 트랜잭션 하나로 실행하고, 교착 상태로 롤백되면 새 트랜잭션에서 다시 시도한다.
     * 실패한 트랜잭션은 재사용하지 않는다.
     */
    private <T> T retryOnDeadlock(String action, Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> work.get());
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.info("{} 중 DB 교착 상태, 다시 시도: attempt={}", action, attempt);
            }
        }
    }

    private Jwt decode(String refreshToken) {
        try {
            return jwtTokenProvider.decodeRefreshToken(refreshToken);
        } catch (JwtException e) {
            throw new InvalidRefreshTokenException();
        }
    }
}
