package com.puppykindergarten.home;

import com.puppykindergarten.user.Role;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 역할에 맞는 {@link HomeProvider} 를 골라 홈 데이터를 만든다.
 * 역할별 분기를 if/else 로 늘어놓지 않고, 역할 → 구현체 매핑으로 둔다.
 */
@Service
public class HomeService {

    private final Map<Role, HomeProvider> providers = new EnumMap<>(Role.class);

    public HomeService(List<HomeProvider> homeProviders) {
        for (HomeProvider provider : homeProviders) {
            HomeProvider previous = providers.put(provider.role(), provider);
            if (previous != null) {
                throw new IllegalStateException("역할 " + provider.role() + " 의 HomeProvider 가 둘 이상입니다.");
            }
        }
        // 역할이 추가됐는데 홈 구현을 빠뜨리면 기동 시점에 바로 드러나게 한다.
        for (Role role : Role.values()) {
            if (!providers.containsKey(role)) {
                throw new IllegalStateException("역할 " + role + " 의 HomeProvider 가 없습니다.");
            }
        }
    }

    public HomeResponse getHome(Long userId, Role role) {
        return providers.get(role).getHome(userId);
    }
}
