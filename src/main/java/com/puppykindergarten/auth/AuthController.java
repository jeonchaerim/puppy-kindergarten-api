package com.puppykindergarten.auth;

import com.puppykindergarten.auth.token.AuthTokenService;
import com.puppykindergarten.auth.token.TokenPair;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 토큰 재발급과 로그아웃. 두 API 모두 Refresh Token 을 가진 것 자체가 자격이므로
 * Access Token 없이(만료된 뒤에도) 호출할 수 있다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthTokenService authTokenService;

    public AuthController(AuthTokenService authTokenService) {
        this.authTokenService = authTokenService;
    }

    /** Refresh Token 을 새 Access/Refresh Token 쌍으로 교체한다. */
    @PostMapping("/reissue")
    public ResponseEntity<TokenPair> reissue(@Valid @RequestBody RefreshTokenRequest request) {
        TokenPair tokens = authTokenService.reissue(request.refreshToken());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens);
    }

    /** Refresh Token 을 폐기한다. 이미 발급된 Access Token 은 만료 시각까지 유효하다. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authTokenService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    public record RefreshTokenRequest(@NotBlank @Size(max = 2048) String refreshToken) {
    }
}
