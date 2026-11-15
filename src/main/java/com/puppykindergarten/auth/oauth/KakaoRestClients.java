package com.puppykindergarten.auth.oauth;

import com.puppykindergarten.auth.AuthProperties;
import java.util.List;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.DefaultAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

/**
 * 카카오 서버를 호출하는 HTTP 클라이언트. 기본 구현에는 제한 시간이 없어서,
 * 카카오가 응답하지 않으면 로그인 요청이 끝없이 대기한다. 두 호출 모두 연결/응답 제한 시간을 건다.
 */
@Component
public class KakaoRestClients {

    private final AuthProperties authProperties;

    public KakaoRestClients(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    /** 인가 코드 → 카카오 토큰 교환 (POST /oauth/token). */
    public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient() {
        RestTemplate restTemplate = new RestTemplate(
                List.of(new FormHttpMessageConverter(), new OAuth2AccessTokenResponseHttpMessageConverter()));
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        restTemplate.setRequestFactory(requestFactory());

        DefaultAuthorizationCodeTokenResponseClient client = new DefaultAuthorizationCodeTokenResponseClient();
        client.setRestOperations(restTemplate);
        return client;
    }

    /** 사용자 정보 조회 (GET /v2/user/me). */
    public RestOperations userInfoRestOperations() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        restTemplate.setRequestFactory(requestFactory());
        return restTemplate;
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(authProperties.kakaoConnectTimeout());
        factory.setReadTimeout(authProperties.kakaoReadTimeout());
        return factory;
    }
}
