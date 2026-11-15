package com.puppykindergarten.auth;

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

    public boolean isDirector(Long kakaoId) {
        return directorKakaoIds.contains(kakaoId);
    }
}
