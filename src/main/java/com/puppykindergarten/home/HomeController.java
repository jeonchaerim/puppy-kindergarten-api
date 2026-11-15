package com.puppykindergarten.home;

import com.puppykindergarten.auth.jwt.JwtTokenProvider;
import com.puppykindergarten.user.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    private final HomeService homeService;

    public HomeController(HomeService homeService) {
        this.homeService = homeService;
    }

    /**
     * 역할별 홈. 누구의 어떤 홈인지는 요청 파라미터가 아니라 검증된 JWT 의 sub, role 클레임으로만 정한다.
     */
    @GetMapping("/api/home")
    public HomeResponse home(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return homeService.getHome(userId, roleOf(jwt));
    }

    private Role roleOf(Jwt jwt) {
        try {
            return Role.valueOf(jwt.getClaimAsString(JwtTokenProvider.CLAIM_ROLE));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AccessDeniedException("알 수 없는 역할입니다.");
        }
    }
}
