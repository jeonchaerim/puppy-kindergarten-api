package com.puppykindergarten.home;

import com.puppykindergarten.user.Role;

/**
 * 역할 하나의 홈 화면 데이터를 만든다. 역할이 늘어나면 구현체를 하나 추가하면 된다.
 */
public interface HomeProvider {

    /** 이 구현체가 담당하는 역할. */
    Role role();

    HomeResponse getHome(Long userId);
}
