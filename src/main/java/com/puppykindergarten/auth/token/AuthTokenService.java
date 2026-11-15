package com.puppykindergarten.auth.token;

import com.puppykindergarten.auth.jwt.JwtTokenProvider;
import com.puppykindergarten.auth.jwt.JwtTokenProvider.IssuedRefreshToken;
import com.puppykindergarten.user.Role;
import com.puppykindergarten.user.User;
import com.puppykindergarten.user.UserRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access/Refresh Token 의 발급, 재발급, 폐기.
 *
 * <ul>
 *   <li>발급: Refresh Token 은 해시로 저장해 둔다.</li>
 *   <li>재발급: 제시된 Refresh Token 을 폐기하고 새 쌍으로 교체한다. (1회용)</li>
 *   <li>로그아웃: 제시된 Refresh Token 을 폐기한다. Access Token 은 만료 시각까지 유효하다.</li>
 * </ul>
 */
@Service
public class AuthTokenService {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenService.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public AuthTokenService(JwtTokenProvider jwtTokenProvider, RefreshTokenRepository refreshTokenRepository,
                            UserRepository userRepository, Clock clock) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional
    public TokenPair issue(Long userId, Role role) {
        // 만료돼서 더는 쓸 수 없는 기록은 발급 시점에 정리한다.
        refreshTokenRepository.deleteExpiredByUserId(userId, clock.instant());

        String accessToken = jwtTokenProvider.createAccessToken(userId, role);
        IssuedRefreshToken refreshToken = jwtTokenProvider.createRefreshToken(userId);

        refreshTokenRepository.save(new RefreshToken(
                userId, TokenHasher.sha256Hex(refreshToken.value()), refreshToken.expiresAt(), clock.instant()));

        return TokenPair.bearer(accessToken, jwtTokenProvider.accessTtl().toSeconds(), refreshToken.value());
    }

    /**
     * Refresh Token 으로 새 토큰 쌍을 발급한다. 사용한 Refresh Token 은 즉시 폐기된다.
     * 재사용 감지 시의 전체 폐기가 롤백되지 않도록 InvalidRefreshTokenException 은 롤백 대상에서 뺀다.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenPair reissue(String refreshToken) {
        Jwt jwt = decode(refreshToken);
        Long userId = Long.valueOf(jwt.getSubject());

        int revoked = refreshTokenRepository.deleteByTokenHash(TokenHasher.sha256Hex(refreshToken));
        if (revoked == 0) {
            // 서명은 유효한데 저장소에 없다 = 이미 교체됐거나 로그아웃된 토큰이 다시 제시됐다.
            // 탈취 가능성이 있으므로 해당 사용자의 Refresh Token 을 모두 폐기해 재로그인을 요구한다.
            int revokedAll = refreshTokenRepository.deleteAllByUserId(userId);
            log.warn("폐기된 Refresh Token 재사용 감지: userId={}, 함께 폐기한 토큰 수={}", userId, revokedAll);
            throw new InvalidRefreshTokenException();
        }

        // 역할은 토큰이 아니라 DB 의 현재 값을 기준으로 다시 담는다.
        User user = userRepository.findById(userId).orElseThrow(InvalidRefreshTokenException::new);
        return issue(user.getId(), user.getRole());
    }

    /** 로그아웃. 이미 폐기된 토큰이어도 조용히 성공한다. (멱등) */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenRepository.deleteByTokenHash(TokenHasher.sha256Hex(refreshToken));
    }

    private Jwt decode(String refreshToken) {
        try {
            return jwtTokenProvider.decodeRefreshToken(refreshToken);
        } catch (JwtException e) {
            throw new InvalidRefreshTokenException();
        }
    }
}
