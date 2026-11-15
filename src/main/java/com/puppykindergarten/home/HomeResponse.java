package com.puppykindergarten.home;

import java.util.List;

/**
 * 홈 응답. 같은 API(GET /api/home)가 역할에 따라 서로 다른 모양을 돌려준다.
 */
public sealed interface HomeResponse {

    String role();

    /** 견주 홈: 내 반려견과 주변 유치원. */
    record OwnerHome(String role, List<MyDog> myDogs, List<NearbyKindergarten> nearbyKindergartens)
            implements HomeResponse {
    }

    /** 원장 홈: 유치원 이름, 등록된 원생, 오늘 등원 수. */
    record DirectorHome(String role, String kindergartenName, List<EnrolledDog> enrolledDogs, int todayAttendance)
            implements HomeResponse {
    }

    record MyDog(Long id, String name, String breed, int age) {
    }

    record NearbyKindergarten(Long id, String name, String address, double distanceKm) {
    }

    record EnrolledDog(Long id, String name, String breed, String ownerName, boolean attendedToday) {
    }
}
