package com.puppykindergarten.home;

import com.puppykindergarten.home.HomeResponse.DirectorHome;
import com.puppykindergarten.home.HomeResponse.EnrolledDog;
import com.puppykindergarten.user.Role;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 원장 홈. 데이터는 더미이며, 실제 서비스라면 userId 로 운영 중인 유치원과 원생, 출석 기록을 조회한다.
 */
@Component
public class DirectorHomeProvider implements HomeProvider {

    @Override
    public Role role() {
        return Role.DIRECTOR;
    }

    @Override
    public HomeResponse getHome(Long userId) {
        List<EnrolledDog> enrolledDogs = List.of(
                new EnrolledDog(1L, "초코", "푸들", "김견주", true),
                new EnrolledDog(2L, "보리", "말티즈", "김견주", true),
                new EnrolledDog(3L, "콩이", "시바견", "이보호", true),
                new EnrolledDog(4L, "두부", "비숑 프리제", "박산책", false),
                new EnrolledDog(5L, "모카", "웰시코기", "최간식", true),
                new EnrolledDog(6L, "별이", "포메라니안", "정터그", true));
        int todayAttendance = (int) enrolledDogs.stream().filter(EnrolledDog::attendedToday).count();
        return new DirectorHome(role().name(), "해피퍼피 유치원", enrolledDogs, todayAttendance);
    }
}
