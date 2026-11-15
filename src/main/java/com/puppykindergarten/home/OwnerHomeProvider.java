package com.puppykindergarten.home;

import com.puppykindergarten.home.HomeResponse.MyDog;
import com.puppykindergarten.home.HomeResponse.NearbyKindergarten;
import com.puppykindergarten.home.HomeResponse.OwnerHome;
import com.puppykindergarten.user.Role;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 견주 홈. 데이터는 더미이며, 실제 서비스라면 userId 로 반려견과 위치 기반 유치원을 조회한다.
 */
@Component
public class OwnerHomeProvider implements HomeProvider {

    @Override
    public Role role() {
        return Role.OWNER;
    }

    @Override
    public HomeResponse getHome(Long userId) {
        List<MyDog> myDogs = List.of(
                new MyDog(1L, "초코", "푸들", 3),
                new MyDog(2L, "보리", "말티즈", 5));
        List<NearbyKindergarten> nearbyKindergartens = List.of(
                new NearbyKindergarten(10L, "해피퍼피 유치원", "서울 마포구 연남로 12", 0.8),
                new NearbyKindergarten(11L, "멍멍 놀이학교", "서울 마포구 성미산로 45", 1.4),
                new NearbyKindergarten(12L, "도그플레이 아카데미", "서울 서대문구 연희로 7", 2.3));
        return new OwnerHome(role().name(), myDogs, nearbyKindergartens);
    }
}
