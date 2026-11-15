package com.puppykindergarten.auth.oauth;

import java.util.Map;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * 카카오 사용자 정보 응답(GET /v2/user/me)에서 필요한 값만 꺼낸다.
 *
 * <pre>
 * { "id": 123, "kakao_account": { "profile": { "nickname": "..." } } }
 * </pre>
 */
public record KakaoUserInfo(Long kakaoId, String nickname) {

    private static final int NICKNAME_MAX_LENGTH = 50;

    public static KakaoUserInfo from(Map<String, Object> attributes) {
        Object id = attributes.get("id");
        if (!(id instanceof Number number)) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_user_info", "카카오 응답에 회원번호(id)가 없습니다.", null));
        }
        long kakaoId = number.longValue();
        return new KakaoUserInfo(kakaoId, extractNickname(attributes, kakaoId));
    }

    private static String extractNickname(Map<String, Object> attributes, long kakaoId) {
        String nickname = null;
        if (attributes.get("kakao_account") instanceof Map<?, ?> account
                && account.get("profile") instanceof Map<?, ?> profile
                && profile.get("nickname") instanceof String value) {
            nickname = value.strip();
        }
        // 닉네임 제공에 동의하지 않은 경우를 대비한 기본값
        if (nickname == null || nickname.isEmpty()) {
            return "user-" + kakaoId;
        }
        return nickname.length() > NICKNAME_MAX_LENGTH ? nickname.substring(0, NICKNAME_MAX_LENGTH) : nickname;
    }
}
