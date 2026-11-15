package com.puppykindergarten.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/**
 * 카카오 사용자 정보 응답(GET /v2/user/me)이 예상과 다를 때의 처리.
 */
class KakaoUserInfoTest {

    @Test
    @DisplayName("회원번호와 닉네임을 꺼낸다")
    void extractsIdAndNickname() {
        KakaoUserInfo info = KakaoUserInfo.from(response(1001L, "초코아빠"));

        assertThat(info.kakaoId()).isEqualTo(1001L);
        assertThat(info.nickname()).isEqualTo("초코아빠");
    }

    @Test
    @DisplayName("회원번호(id)가 없거나 숫자가 아니면 로그인 실패로 처리한다")
    void rejectsMissingOrInvalidId() {
        Map<String, Object> noId = response(null, "초코아빠");
        Map<String, Object> textId = response(null, "초코아빠");
        textId.put("id", "1001");

        for (Map<String, Object> attributes : new Map[]{noId, textId}) {
            assertThatThrownBy(() -> KakaoUserInfo.from(attributes))
                    .isInstanceOf(OAuth2AuthenticationException.class)
                    .satisfies(e -> assertThat(((OAuth2AuthenticationException) e).getError().getErrorCode())
                            .isEqualTo("invalid_user_info"));
        }
    }

    @Test
    @DisplayName("닉네임 제공에 동의하지 않았으면 user-{카카오ID} 로 대체한다")
    void fallsBackWhenNicknameMissing() {
        Map<String, Object> noAccount = new HashMap<>(Map.of("id", 1001L));
        Map<String, Object> noProfile = new HashMap<>(Map.of("id", 1001L, "kakao_account", Map.of()));
        Map<String, Object> blankNickname = response(1001L, "   ");

        assertThat(KakaoUserInfo.from(noAccount).nickname()).isEqualTo("user-1001");
        assertThat(KakaoUserInfo.from(noProfile).nickname()).isEqualTo("user-1001");
        assertThat(KakaoUserInfo.from(blankNickname).nickname()).isEqualTo("user-1001");
    }

    @Test
    @DisplayName("닉네임 앞뒤 공백은 지우고, 50자를 넘으면 DB 컬럼 길이에 맞춰 자른다")
    void trimsAndTruncatesNickname() {
        String longNickname = "가".repeat(60);

        assertThat(KakaoUserInfo.from(response(1001L, "  초코아빠  ")).nickname()).isEqualTo("초코아빠");
        assertThat(KakaoUserInfo.from(response(1001L, longNickname)).nickname()).hasSize(50);
    }

    private static Map<String, Object> response(Long id, String nickname) {
        Map<String, Object> attributes = new HashMap<>();
        if (id != null) {
            attributes.put("id", id);
        }
        attributes.put("kakao_account", Map.of("profile", Map.of("nickname", nickname)));
        return attributes;
    }
}
