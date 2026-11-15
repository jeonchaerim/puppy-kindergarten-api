package com.puppykindergarten.user;

/**
 * 사용자 역할. 견주(OWNER) / 원장(DIRECTOR).
 */
public enum Role {
    OWNER,
    DIRECTOR;

    /** Spring Security 권한 문자열 (예: ROLE_OWNER). */
    public String authority() {
        return "ROLE_" + name();
    }
}
