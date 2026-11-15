package com.puppykindergarten.user;

import com.puppykindergarten.auth.jwt.JwtTokenProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 토큰의 주인(sub)에 해당하는 내 정보. */
    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        Long userId = JwtTokenProvider.userIdOf(jwt);
        return userRepository.findById(userId)
                .map(user -> new MeResponse(user.getId(), user.getKakaoId(), user.getNickname(), user.getRole().name()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    }

    /** kakaoId 는 원장 지정(DIRECTOR_KAKAO_IDS)에 쓰는 카카오 회원번호다. */
    public record MeResponse(Long userId, Long kakaoId, String nickname, String role) {
    }
}
