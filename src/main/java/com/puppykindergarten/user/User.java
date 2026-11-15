package com.puppykindergarten.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_kakao_id", columnNames = "kakao_id"))
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 카카오 회원번호. 카카오 계정을 식별하는 유일한 키. */
    @Column(name = "kakao_id", nullable = false, updatable = false)
    private Long kakaoId;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_login_at", nullable = false)
    private LocalDateTime lastLoginAt;

    protected User() {
    }

    private User(Long kakaoId, String nickname, Role role, LocalDateTime now) {
        this.kakaoId = kakaoId;
        this.nickname = nickname;
        this.role = role;
        this.createdAt = now;
        this.lastLoginAt = now;
    }

    public static User register(Long kakaoId, String nickname, Role role, LocalDateTime now) {
        return new User(kakaoId, nickname, role, now);
    }

    /** 로그인할 때마다 프로필과 역할을 최신 상태로 맞춘다. */
    public void recordLogin(String nickname, Role role, LocalDateTime now) {
        this.nickname = nickname;
        this.role = role;
        this.lastLoginAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getKakaoId() {
        return kakaoId;
    }

    public String getNickname() {
        return nickname;
    }

    public Role getRole() {
        return role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }
}
