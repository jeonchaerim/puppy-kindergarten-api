package com.puppykindergarten.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * 테스트용 가짜 카카오 서버. 토큰 발급(/oauth/token)과 사용자 정보(/v2/user/me)만 흉내 낸다.
 * 실제 카카오를 호출하지 않고 인가 코드 → 토큰 교환 → 사용자 조회 흐름 전체를 검증하기 위함.
 */
class FakeKakaoServer {

    static final String ACCESS_TOKEN = "fake-kakao-access-token";

    private final HttpServer server;
    private final List<String> tokenRequestBodies = Collections.synchronizedList(new ArrayList<>());
    private final List<String> userInfoAuthorizationHeaders = Collections.synchronizedList(new ArrayList<>());

    private volatile String userInfoJson = "{}";
    private volatile boolean tokenEndpointFails = false;
    private volatile long tokenDelayMillis = 0;
    private volatile long userInfoDelayMillis = 0;

    FakeKakaoServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/oauth/token", this::handleToken);
        server.createContext("/v2/user/me", this::handleUserInfo);
        // 응답을 지연시키는 요청이 다른 테스트의 요청을 막지 않도록 요청마다 스레드를 쓴다.
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        tokenRequestBodies.clear();
        userInfoAuthorizationHeaders.clear();
        userInfoJson = "{}";
        tokenEndpointFails = false;
        tokenDelayMillis = 0;
        userInfoDelayMillis = 0;
    }

    /** 토큰 교환 응답을 늦춘다. (카카오 장애 흉내) */
    void delayTokenEndpoint(long millis) {
        tokenDelayMillis = millis;
    }

    /** 사용자 정보 응답을 늦춘다. */
    void delayUserInfoEndpoint(long millis) {
        userInfoDelayMillis = millis;
    }

    void respondWithUser(long kakaoId, String nickname) {
        userInfoJson = """
                {"id": %d, "kakao_account": {"profile": {"nickname": "%s"}}}
                """.formatted(kakaoId, nickname);
    }

    void failTokenEndpoint() {
        tokenEndpointFails = true;
    }

    List<String> tokenRequestBodies() {
        return List.copyOf(tokenRequestBodies);
    }

    List<String> userInfoAuthorizationHeaders() {
        return List.copyOf(userInfoAuthorizationHeaders);
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        tokenRequestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        sleep(tokenDelayMillis);
        if (tokenEndpointFails) {
            respond(exchange, 400, """
                    {"error": "invalid_grant", "error_description": "authorization code not found"}
                    """);
            return;
        }
        respond(exchange, 200, """
                {"access_token": "%s", "token_type": "bearer", "expires_in": 3600}
                """.formatted(ACCESS_TOKEN));
    }

    private void handleUserInfo(HttpExchange exchange) throws IOException {
        userInfoAuthorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        sleep(userInfoDelayMillis);
        respond(exchange, 200, userInfoJson);
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            // 지연 테스트에서는 클라이언트가 제한 시간 초과로 이미 연결을 끊었을 수 있다.
        }
    }
}
