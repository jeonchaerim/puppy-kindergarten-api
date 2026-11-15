package com.puppykindergarten.home;

import com.puppykindergarten.auth.jwt.JwtTokenProvider;
import com.puppykindergarten.user.Role;
import com.puppykindergarten.user.UserRepository;
import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class HomeController {

    private final HomeService homeService;
    private final UserRepository userRepository;

    public HomeController(HomeService homeService, UserRepository userRepository) {
        this.homeService = homeService;
        this.userRepository = userRepository;
    }

    /**
     * 역할별 홈. 누구의 어떤 홈인지는 요청 파라미터가 아니라 검증된 JWT 로만 정한다.
     * (sub → 사용자, role 클레임 → 시큐리티 권한 ROLE_OWNER / ROLE_DIRECTOR)
     */
    @GetMapping("/api/home")
    public HomeResponse home(JwtAuthenticationToken authentication) {
        Long userId = JwtTokenProvider.userIdOf(authentication.getToken());
        // 토큰은 유효해도 회원이 이미 없을 수 있다. (/api/users/me 와 같은 기준)
        if (!userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }
        return homeService.getHome(userId, roleOf(authentication));
    }

    /**
     * 인증 객체의 권한에서 역할을 꺼낸다. role 클레임 해석은 SecurityConfig 의 권한 변환 한 곳에서만 한다.
     * 역할이 없는 요청은 인가 규칙(hasAnyRole)에서 이미 403 으로 걸러지므로 여기까지 오지 않는다.
     */
    private Role roleOf(JwtAuthenticationToken authentication) {
        var authorities = AuthorityUtils.authorityListToSet(authentication.getAuthorities());
        return Arrays.stream(Role.values())
                .filter(role -> authorities.contains(role.authority()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("인가를 통과한 요청에 역할 권한이 없습니다."));
    }
}
