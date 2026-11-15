package com.puppykindergarten.auth.token;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료돼서 더는 쓸 수 없는 Refresh Token 기록을 주기적으로 지운다.
 * (만료된 토큰은 서명 검증 단계에서 이미 거부되므로, 이 작업은 테이블 정리만을 위한 것이다)
 */
@Component
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    public RefreshTokenCleanupJob(RefreshTokenRepository refreshTokenRepository, Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
    }

    /** 매일 새벽 4시. */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public int purgeExpired() {
        int deleted = refreshTokenRepository.deleteExpired(clock.instant());
        log.info("만료된 Refresh Token {}건 정리", deleted);
        return deleted;
    }
}
