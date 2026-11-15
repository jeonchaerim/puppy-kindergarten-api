package com.puppykindergarten.auth.oauth;

import com.puppykindergarten.user.Role;
import com.puppykindergarten.user.User;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * 카카오 로그인에 성공한 우리 서비스 회원. 성공 핸들러가 이 정보로 응답(이후 JWT)을 만든다.
 */
public class KakaoOAuth2User implements OAuth2User {

    private final Long userId;
    private final String nickname;
    private final Role role;
    private final Map<String, Object> attributes;

    public KakaoOAuth2User(User user, Map<String, Object> attributes) {
        this.userId = user.getId();
        this.nickname = user.getNickname();
        this.role = user.getRole();
        this.attributes = Map.copyOf(attributes);
    }

    public Long getUserId() {
        return userId;
    }

    public String getNickname() {
        return nickname;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}
