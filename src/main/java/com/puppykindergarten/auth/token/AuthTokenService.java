package com.puppykindergarten.auth.token;

import com.puppykindergarten.auth.jwt.JwtTokenProvider;
import com.puppykindergarten.auth.jwt.JwtTokenProvider.IssuedRefreshToken;
import com.puppykindergarten.user.Role;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access/Refresh Token 발급. Refresh Token 은 해시로 저장해 두고 재발급·로그아웃 때 대조한다.
 */
@Service
public class AuthTokenService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    public AuthTokenService(JwtTokenProvider jwtTokenProvider, RefreshTokenRepository refreshTokenRepository, Clock clock) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
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
}
