package com.puppykindergarten.auth;

import com.puppykindergarten.user.Role;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 관련 설정.
 *
 * @param directorKakaoIds 원장(ROLE_DIRECTOR)으로 지정할 카카오 회원번호 목록.
 *                         환경변수 DIRECTOR_KAKAO_IDS(쉼표 구분)로 주입한다.
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(Set<Long> directorKakaoIds) {

    public AuthProperties {
        directorKakaoIds = directorKakaoIds == null ? Set.of() : Set.copyOf(directorKakaoIds);
    }

    /**
     * 역할은 사용자가 고를 수 없다. 서버에 사전 지정된 카카오 회원번호만 원장이 되고, 그 외에는 모두 견주다.
     * 로그인과 토큰 재발급 때마다 이 기준으로 다시 계산한다.
     */
    public Role resolveRole(Long kakaoId) {
        return directorKakaoIds.contains(kakaoId) ? Role.DIRECTOR : Role.OWNER;
    }
}
