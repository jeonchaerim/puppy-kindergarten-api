package com.puppykindergarten.auth.oauth;

import com.puppykindergarten.auth.KakaoLoginService;
import com.puppykindergarten.user.User;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * 카카오 access token 으로 사용자 정보를 조회한 뒤 우리 서비스 회원과 연결한다.
 */
@Service
public class KakaoOAuth2UserService extends DefaultOAuth2UserService {

    private final KakaoLoginService kakaoLoginService;

    public KakaoOAuth2UserService(KakaoLoginService kakaoLoginService) {
        this.kakaoLoginService = kakaoLoginService;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User kakaoUser = super.loadUser(userRequest);
        KakaoUserInfo info = KakaoUserInfo.from(kakaoUser.getAttributes());
        User user = kakaoLoginService.loginOrRegister(info.kakaoId(), info.nickname());
        return new KakaoOAuth2User(user, kakaoUser.getAttributes());
    }
}
