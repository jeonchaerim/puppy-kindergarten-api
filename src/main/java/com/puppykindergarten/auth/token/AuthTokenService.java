package com.puppykindergarten.auth.token;

import com.puppykindergarten.auth.AuthProperties;
import com.puppykindergarten.auth.jwt.JwtTokenProvider;
import com.puppykindergarten.auth.jwt.JwtTokenProvider.IssuedRefreshToken;
import com.puppykindergarten.user.Role;
import com.puppykindergarten.user.User;
import com.puppykindergarten.user.UserRepository;
import java.time.Clock;
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

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final AuthProperties authProperties;
    private final Clock clock;

    public AuthTokenService(JwtTokenProvider jwtTokenProvider, RefreshTokenRepository refreshTokenRepository,
                            UserRepository userRepository, AuthProperties authProperties, Clock clock) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.authProperties = authProperties;
        this.clock = clock;
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
    @Transactional
    public TokenPair reissue(String refreshToken) {
        Jwt jwt = decode(refreshToken);
        Long userId = JwtTokenProvider.userIdOf(jwt);

        // 저장소에 없으면 이미 교체됐거나 로그아웃된 토큰이다.
        int revoked = refreshTokenRepository.deleteByTokenHash(TokenHasher.sha256Hex(refreshToken));
        if (revoked == 0) {
            throw new InvalidRefreshTokenException();
        }

        User user = userRepository.findById(userId).orElseThrow(InvalidRefreshTokenException::new);
        // 로그인 때와 같은 기준으로 역할을 다시 계산한다.
        // (원장 목록에서 빠진 사용자가 재발급만으로 원장 권한을 유지하지 못하게 한다)
        Role role = authProperties.resolveRole(user.getKakaoId());
        user.syncRole(role);
        return issue(user.getId(), role);
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
