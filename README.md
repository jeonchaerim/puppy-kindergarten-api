# puppy-kindergarten-api

반려견 유치원(견주 ↔ 원장 연결 플랫폼)의 인증/권한 API.

Kakao 소셜 로그인(OAuth 2.0) → 자체 JWT 발급/검증 → 역할(견주/원장) 기반 홈 분기.

- [1. 전체 흐름](#1-전체-흐름)
- [2. 기술 스택과 선택 근거](#2-기술-스택과-선택-근거)
- [3. 실행 방법](#3-실행-방법)
- [4. 환경변수](#4-환경변수)
- [5. API 명세](#5-api-명세)
- [6. 인증/권한 정책](#6-인증권한-정책)
- [7. 테스트와 검증](#7-테스트와-검증)
- [8. Git 전략](#8-git-전략)
- [9. LLM 활용 내역](#9-llm-활용-내역)

---

## 1. 전체 흐름

### 1-1. 로그인 → JWT 발급

```mermaid
sequenceDiagram
    autonumber
    participant C as 클라이언트(브라우저)
    participant S as API 서버
    participant K as Kakao
    participant DB as MySQL

    C->>S: GET /oauth2/authorization/kakao
    S-->>C: 302 카카오 인가 페이지 (client_id, redirect_uri, state)
    C->>K: 카카오 로그인 + 동의
    K-->>C: 302 /login/oauth2/code/kakao?code=...&state=...
    C->>S: GET /login/oauth2/code/kakao?code=...&state=...
    S->>S: state 검증 (CSRF 방어)
    S->>K: POST /oauth/token (code, client_id, client_secret)
    K-->>S: 카카오 access token
    S->>K: GET /v2/user/me (Bearer 카카오 토큰)
    K-->>S: 카카오 회원번호, 닉네임
    S->>DB: 회원 조회 → 없으면 가입, 있으면 닉네임/역할 동기화
    S->>S: 역할 결정 (DIRECTOR_KAKAO_IDS 에 있으면 원장, 아니면 견주)
    S->>DB: Refresh Token 해시 저장
    S-->>C: 200 { accessToken, refreshToken, role, ... }
```

### 1-2. JWT 검증 → 권한 검증 → 역할 분기

```mermaid
flowchart TD
    A["GET /api/home<br/>Authorization: Bearer &lt;JWT&gt;"] --> B{토큰이 있는가}
    B -- 없음 --> E1["401 UNAUTHORIZED"]
    B -- 있음 --> C{"서명(HS256) · issuer ·<br/>token_type=access 검증"}
    C -- 실패 --> E2["401 INVALID_TOKEN"]
    C -- 통과 --> D{만료되었는가}
    D -- 만료 --> E3["401 TOKEN_EXPIRED<br/>→ /api/auth/reissue 로 재발급"]
    D -- 유효 --> F["role 클레임 → 권한<br/>ROLE_OWNER / ROLE_DIRECTOR"]
    F --> G{"/api/home 인가 규칙<br/>OWNER 또는 DIRECTOR"}
    G -- 역할 없음/알 수 없음 --> E4["403 FORBIDDEN"]
    G -- 통과 --> H["HomeService<br/>역할 → HomeProvider 선택"]
    H -- OWNER --> I["OwnerHomeProvider<br/>{ role, myDogs, nearbyKindergartens }"]
    H -- DIRECTOR --> J["DirectorHomeProvider<br/>{ role, kindergartenName, enrolledDogs, todayAttendance }"]
```

### 1-3. 패키지 구조

```
com.puppykindergarten
├── auth
│   ├── oauth    카카오 OAuth2 로그인 (사용자 조회, 성공/실패 핸들러)
│   ├── jwt      자체 JWT 발급/검증
│   ├── token    Refresh Token 저장, 재발급, 로그아웃, 만료 정리
│   ├── AuthController      POST /api/auth/reissue, /api/auth/logout
│   ├── AuthProperties      원장 지정 목록, 역할 결정 기준
│   └── KakaoLoginService   가입/로그인 처리
├── home         역할별 홈 (HomeProvider 구현체 2개)
├── user         User, Role, 내 정보 API
└── config       SecurityConfig, 에러 응답 형식
```

---

## 2. 기술 스택과 선택 근거

| 구분 | 사용 | 버전 |
|---|---|---|
| 언어 | Java | 17 (Gradle toolchain 고정) |
| 프레임워크 | Spring Boot | 3.3.13 |
| 보안 | Spring Security (OAuth2 Client + Resource Server) | 6.3.x (Boot 관리 버전) |
| 빌드 | Gradle (wrapper) | 8.14.3 |
| DB | MySQL + Spring Data JPA | 8.0 |
| JWT | nimbus-jose-jwt | Resource Server 포함 버전 |
| 테스트 | JUnit 5, MockMvc, H2(MySQL 호환 모드) | |

**카카오 로그인: Spring Security OAuth2 Client**
인가 요청 생성, `state` 검증, 토큰 교환, 사용자 정보 조회를 직접 구현하지 않고 검증된 구현(`oauth2Login`)에 맡겼다. 직접 작성한 부분은 카카오 사용자를 우리 회원으로 연결하는 `KakaoOAuth2UserService` 와 성공/실패 핸들러뿐이다. 카카오는 표준 OIDC 공급자 목록에 없어 `application.yml` 에 provider 정보를 등록했고, 클라이언트 인증은 카카오 요구에 맞춰 `client_secret_post` 를 쓴다.

**JWT 검증: Spring Security Resource Server**
Bearer 토큰 추출, 서명·만료 검증, 인증 객체 생성까지 필터를 직접 만들지 않고 `oauth2ResourceServer().jwt()` 를 사용했다. 커스텀 필터에서 흔히 생기는 실수(예외 처리 누락, 필터 순서 오류)를 줄일 수 있다.

**JWT 라이브러리: nimbus-jose-jwt**
Resource Server 스타터에 이미 포함돼 있어, 발급(`NimbusJwtEncoder`)과 검증(`NimbusJwtDecoder`)을 추가 의존성 없이 한 라이브러리로 통일했다. jjwt 를 따로 넣으면 발급과 검증이 서로 다른 라이브러리를 거치게 된다.

**서명 알고리즘: HS256**
토큰을 발급하는 서버와 검증하는 서버가 같은 단일 서비스라 대칭키로 충분하다. 검증 주체가 여럿으로 나뉘면 RS256(공개키 배포)으로 바꾸는 것이 맞다.

**Refresh Token: JWT + DB 저장(해시)**
Refresh Token 도 JWT 로 발급해 서명·만료를 먼저 검증하고, DB 에는 SHA-256 해시만 저장해 재발급·로그아웃 때 대조한다. DB 가 유출돼도 저장된 값으로는 재발급을 요청할 수 없다. 토큰 자체가 서명된 고엔트로피 값이라 솔트·스트레칭 없이 SHA-256 으로 충분하다.

**역할 분기: 역할별 `HomeProvider` 구현체**
컨트롤러·서비스에 `if (role == ...)` 를 두지 않고, 역할 하나당 구현체 하나를 두어 `HomeService` 가 역할 → 구현체 매핑으로 고른다. 역할이 추가되면 구현체만 추가하면 되고, 구현을 빠뜨리면 애플리케이션이 기동되지 않는다.

**테스트 DB: H2(MySQL 호환 모드)**
외부 DB 없이 `./gradlew test` 만으로 전체 테스트가 돈다. 운영 설정(`application.yml`)은 그대로 쓰고 테스트 전용 값만 덮어쓴다.

---

## 3. 실행 방법

### 사전 준비

- JDK 17 (다른 버전이 기본이어도 Gradle toolchain 이 17 을 찾아 사용)
- Docker (MySQL 8.0 컨테이너)
- 카카오 개발자 콘솔([developers.kakao.com](https://developers.kakao.com)) 애플리케이션

### 카카오 애플리케이션 설정

1. 애플리케이션 추가
2. **[앱] > [플랫폼 키] > [REST API 키]** 에서
   - REST API 키 확인 (`KAKAO_CLIENT_ID`)
   - 카카오 로그인 리다이렉트 URI 등록: `http://localhost:8080/login/oauth2/code/kakao`
   - 클라이언트 시크릿 코드 확인 (`KAKAO_CLIENT_SECRET`, 기본 활성화 상태)
3. **[카카오 로그인] > [사용 설정]** 상태 ON
4. **[카카오 로그인] > [동의항목]** 에서 닉네임(`profile_nickname`) 설정

### 실행

```bash
# 1. 환경변수 파일 준비
cp .env.example .env
#    .env 를 열어 값을 채운다. JWT_SECRET 은 아래 명령으로 생성한다.
openssl rand -base64 32

# 2. MySQL 실행
docker compose up -d

# 3. 애플리케이션 실행 (프로젝트 루트의 .env 를 자동으로 읽는다)
./gradlew bootRun
```

### 동작 확인

1. 브라우저에서 `http://localhost:8080/oauth2/authorization/kakao` 접속 → 카카오 로그인
2. 응답 JSON 의 `accessToken` 복사
3. 홈 호출

```bash
curl -H "Authorization: Bearer <accessToken>" http://localhost:8080/api/home
```

### 원장 계정으로 확인하기

역할은 사용자가 고를 수 없고, 서버에 사전 지정한 카카오 회원번호만 원장이 된다.

1. 먼저 견주로 로그인한 뒤 내 정보에서 `kakaoId` 를 확인한다.

```bash
curl -H "Authorization: Bearer <accessToken>" http://localhost:8080/api/users/me
# { "userId": 1, "kakaoId": 1234567890, "nickname": "...", "role": "OWNER" }
```

2. `.env` 에 `DIRECTOR_KAKAO_IDS=1234567890` 을 넣고 애플리케이션을 재시작한다.
3. 다시 카카오 로그인하면(또는 토큰을 재발급하면) `role` 이 `DIRECTOR` 인 토큰이 발급되고, 같은 `/api/home` 이 원장 응답을 준다.

### 테스트

```bash
./gradlew test
```

MySQL·카카오 없이 실행된다(H2, 가짜 카카오 서버 사용).

---

## 4. 환경변수

시크릿은 코드와 설정 파일에 두지 않고 환경변수로만 주입한다. `.env` 는 `.gitignore` 대상이며 저장소에는 [.env.example](.env.example) 만 있다.

| 이름 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `DB_USERNAME` | O | | MySQL 사용자 |
| `DB_PASSWORD` | O | | MySQL 비밀번호 |
| `DB_ROOT_PASSWORD` | O (docker-compose) | | MySQL root 비밀번호 |
| `DB_HOST` | | `localhost` | MySQL 호스트 |
| `DB_PORT` | | `3306` | MySQL 포트 |
| `DB_NAME` | | `puppy_kindergarten` | 데이터베이스 이름 |
| `KAKAO_CLIENT_ID` | O | | 카카오 REST API 키 |
| `KAKAO_CLIENT_SECRET` | O | | 카카오 Client Secret |
| `JWT_SECRET` | O | | HS256 서명키. Base64 인코딩, 디코딩 기준 32바이트 이상 |
| `JWT_ISSUER` | | `puppy-kindergarten-api` | `iss` 클레임 |
| `JWT_ACCESS_TTL` | | `15m` | Access Token 유효 시간 |
| `JWT_REFRESH_TTL` | | `14d` | Refresh Token 유효 시간 |
| `DIRECTOR_KAKAO_IDS` | | (비어 있음) | 원장으로 지정할 카카오 회원번호. 쉼표 구분 |
| `JPA_DDL_AUTO` | | `update` | 스키마 자동 생성 방식 |
| `KAKAO_CONNECT_TIMEOUT` | | `3s` | 카카오 서버 연결 제한 시간 |
| `KAKAO_READ_TIMEOUT` | | `5s` | 카카오 서버 응답 대기 제한 시간 |
| `SERVER_PORT` | | `8080` | 서버 포트 |

필수 값이 없으면 애플리케이션이 기동되지 않는다. `JWT_SECRET` 은 형식이 틀리거나 32바이트보다 짧아도 기동에 실패한다.

---

## 5. API 명세

### 요약

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| GET | `/oauth2/authorization/kakao` | 없음 | 카카오 로그인 시작 (카카오로 리다이렉트) |
| GET | `/login/oauth2/code/kakao` | 없음 | 카카오 콜백. 가입/로그인 처리 후 JWT 발급 |
| POST | `/api/auth/reissue` | Refresh Token | Access/Refresh Token 재발급 |
| POST | `/api/auth/logout` | Refresh Token | 로그아웃 (Refresh Token 폐기) |
| GET | `/api/users/me` | Bearer | 내 정보 |
| GET | `/api/home` | Bearer (OWNER, DIRECTOR) | 역할별 홈 |

### 로그인 콜백 응답 — `GET /login/oauth2/code/kakao`

```json
{
  "tokenType": "Bearer",
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "accessTokenExpiresIn": 900,
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "userId": 1,
  "nickname": "초코아빠",
  "role": "OWNER"
}
```

프론트엔드가 없는 과제 범위라 토큰을 콜백 응답 본문으로 내려준다. 응답에는 `Cache-Control: no-store` 가 붙는다.

### 재발급 — `POST /api/auth/reissue`

요청
```json
{ "refreshToken": "eyJhbGciOiJIUzI1NiJ9..." }
```
응답 200
```json
{
  "tokenType": "Bearer",
  "accessToken": "...",
  "accessTokenExpiresIn": 900,
  "refreshToken": "..."
}
```

### 로그아웃 — `POST /api/auth/logout`

요청
```json
{ "refreshToken": "eyJhbGciOiJIUzI1NiJ9..." }
```
응답 204 (본문 없음)

### 내 정보 — `GET /api/users/me`

```json
{ "userId": 1, "kakaoId": 1234567890, "nickname": "초코아빠", "role": "OWNER" }
```

### 홈 — `GET /api/home`

같은 API 가 JWT 의 `role` 에 따라 다른 응답을 준다. 데이터는 더미다.

견주 (`role=OWNER`)
```json
{
  "role": "OWNER",
  "myDogs": [
    { "id": 1, "name": "초코", "breed": "푸들", "age": 3 },
    { "id": 2, "name": "보리", "breed": "말티즈", "age": 5 }
  ],
  "nearbyKindergartens": [
    { "id": 10, "name": "해피퍼피 유치원", "address": "서울 마포구 연남로 12", "distanceKm": 0.8 }
  ]
}
```

원장 (`role=DIRECTOR`)
```json
{
  "role": "DIRECTOR",
  "kindergartenName": "해피퍼피 유치원",
  "enrolledDogs": [
    { "id": 1, "name": "초코", "breed": "푸들", "ownerName": "김견주", "attendedToday": true }
  ],
  "todayAttendance": 5
}
```

### 에러 응답

모든 에러는 같은 형식이다.

```json
{ "code": "TOKEN_EXPIRED", "message": "토큰이 만료되었습니다." }
```

| HTTP | code | 상황 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 요청 본문이 없거나 형식이 틀림 |
| 401 | `UNAUTHORIZED` | 토큰 없이 보호된 API 호출 |
| 401 | `TOKEN_EXPIRED` | Access Token 만료 → 재발급 필요 |
| 401 | `INVALID_TOKEN` | 위조, 형식 오류, Refresh Token 을 Bearer 로 사용 등 |
| 401 | `INVALID_REFRESH_TOKEN` | Refresh Token 이 위조·만료·이미 사용됨·로그아웃됨 |
| 401 | `KAKAO_LOGIN_FAILED` | 카카오 로그인 실패 (취소, state 불일치, 코드 거부 등) |
| 503 | `KAKAO_UNAVAILABLE` | 카카오 서버에 연결하지 못했거나 제한 시간 안에 응답이 없음 |
| 500 | `INTERNAL_ERROR` | 서버 내부 오류 (로그인 중 회원·토큰 저장 실패 등) |
| 403 | `FORBIDDEN` | 역할이 없거나 권한 부족 |
| 404 | `NOT_FOUND` | 토큰은 유효하지만 회원이 없음 |

---

## 6. 인증/권한 정책

### JWT

| | Access Token | Refresh Token |
|---|---|---|
| 유효 시간(기본) | 15분 | 14일 |
| 클레임 | `sub`(사용자 ID), `role`, `token_type=access`, `iss`, `iat`, `exp`, `jti` | `sub`, `token_type=refresh`, `iss`, `iat`, `exp`, `jti` |
| 서버 저장 | 저장 안 함 | SHA-256 해시만 MySQL 에 저장 |
| 용도 | API 인증 (`Authorization: Bearer`) | 재발급, 로그아웃 |

- 알고리즘은 HS256 으로 고정한다(`alg=none` 이나 다른 알고리즘의 토큰 거부).
- `token_type` 으로 두 토큰을 구분해, Refresh Token 을 API 인증에 쓰거나 Access Token 으로 재발급받을 수 없다.
- 만료 검증에 허용 오차를 두지 않는다. 만료 시각이 지나면 바로 거부한다.

### 역할 부여

- 최초 로그인 시 기본 역할은 `ROLE_OWNER`(견주)다. 단, `DIRECTOR_KAKAO_IDS`에 사전 지정된 카카오 회원번호는 첫 로그인부터 `ROLE_DIRECTOR`(원장)로 가입한다.
- `ROLE_DIRECTOR`(원장)는 사용자가 선택할 수 없고, 서버 환경변수 `DIRECTOR_KAKAO_IDS` 에 사전 지정한 카카오 회원번호에만 부여한다.
- 역할은 **로그인할 때와 토큰을 재발급할 때마다** 이 목록 기준으로 다시 계산해 DB 와 토큰에 반영한다.

### 동시 최초 로그인

- 같은 카카오 계정의 최초 로그인이 동시에 들어와도 `users.kakao_id` 유일 제약으로 회원은 한 명만 저장한다.
- 두 요청이 모두 회원이 없다고 조회한 뒤 가입을 시도하면, 중복 삽입에 실패한 트랜잭션을 먼저 롤백한다.
- 새 트랜잭션에서 먼저 가입된 회원을 조회하고 프로필·역할을 동기화해 두 요청 모두 같은 회원 ID로 로그인한다.
- 재조회해도 해당 회원이 없으면 원래 무결성 오류를 전달한다. 중복 가입과 관계없는 DB 오류를 정상 로그인으로 처리하지 않는다.

### 재발급

- 재발급은 교체다. 사용한 Refresh Token 은 즉시 폐기하고 새 Access/Refresh 쌍을 발급한다(1회용).
- 새 Refresh Token의 만료일은 재발급 시점부터 기본 14일이다. 최초 로그인 기준의 절대 만료 제한은 없으므로, 유효 기간 안에 재발급을 반복하면 로그인 상태를 계속 유지할 수 있다.
- 폐기는 단일 `DELETE` 의 삭제 행 수로 판정한다. 같은 토큰으로 요청이 동시에 와도 한 요청만 성공하고 나머지는 401 이다.
- 이미 사용했거나 로그아웃한 토큰이 다시 제시되면 그 요청만 401 로 거부한다. 다른 기기의 토큰은 유지된다.
- Access Token 이 만료된 뒤 호출하는 API 이므로 Access Token 을 요구하지 않으며, 요청에 Authorization 헤더가 붙어 있어도 읽지 않는다.

### 로그아웃

- 제시한 Refresh Token 을 폐기한다. 이후 그 토큰으로는 재발급할 수 없다.
- **이미 발급된 Access Token 은 만료 시각(기본 15분)까지 유효하다.** 서버가 Access Token 을 저장하지 않으므로 즉시 차단하지 않는다. 즉 로그아웃 후에도 최대 15분 동안은 기존 Access Token 으로 API 를 호출할 수 있다.
- 로그아웃은 제시한 토큰만 폐기한다. 같은 사용자의 다른 기기는 로그인 상태가 유지된다.
- 같은 이유로, 재발급으로 이미 교체된 이전 Refresh Token 으로 로그아웃하면 아무것도 폐기되지 않고, 교체로 받은 새 Refresh Token 은 그대로 유효하다. 로그아웃은 가장 최근에 받은 Refresh Token 으로 요청해야 한다.
- 이미 폐기된 토큰으로 다시 호출해도 204 를 응답한다(멱등).

### 역할 변경의 반영 시점

- `DIRECTOR_KAKAO_IDS` 변경은 `.env` 또는 환경변수를 수정한 뒤 애플리케이션을 재시작해 반영한다. 파일만 수정하면 실행 중인 서버의 역할 목록은 바뀌지 않는다.
- `/api/home` 은 DB 가 아니라 **JWT 의 `role` 클레임**으로 분기한다.
- 따라서 역할이 바뀌어도(원장 목록에서 추가/제외) 이미 발급된 Access Token 은 만료될 때까지 이전 역할로 동작한다. 다음 재발급 또는 로그인부터 새 역할이 반영된다(최대 15분).

### 그 외

- Access/Refresh Token 모두 `exp` 와 `sub`(양의 정수 사용자 ID)가 없으면 서명이 유효해도 거절한다.
- 카카오 호출(토큰 교환, 사용자 정보 조회)에는 연결 3초, 응답 5초 제한이 있다. 초과하면 로그인 실패(401)와 구분해 503 `KAKAO_UNAVAILABLE` 로 응답한다.
- 로그인 처리 중 DB 오류는 인증 실패와 구분해 500 `INTERNAL_ERROR` 로 응답한다.
- 인증 상태는 서버 세션에 저장하지 않는다(`STATELESS`). 카카오 로그인 중 `state` 보관에만 잠깐 쓰인 세션은 로그인 성공 시 폐기한다.
- 만료된 Refresh Token 기록은 매일 새벽 4시에 정리한다.
- 로그인 실패 원인, Refresh Token 이 거부된 구체적 이유는 응답에 드러내지 않는다. 로그에도 카카오가 보낸 메시지 원문 대신 오류 코드만 남기고, 토큰 값은 남기지 않는다.

---

## 7. 테스트와 검증

`./gradlew test` — 67개.

| 테스트 클래스 | 수 | 검증 내용 |
|---|---|---|
| `KakaoLoginFlowTest` | 15 | 인가 요청 리다이렉트부터 콜백, 가입, JWT 발급, 역할별 홈까지 전체 흐름. 카카오 지연(503), 로그인 중 DB 오류(500) |
| `KakaoLoginServiceTest` | 6 | 최초 가입, 순차·동시 로그인 중복 가입 방지, 원장 지정, 역할 동기화, 무결성 오류 전달 |
| `JwtTokenProviderTest` | 12 | 클레임, 만료, 위조, 변조, `alg=none`, 토큰 종류 교차 사용, 약한 키 |
| `BearerAuthenticationTest` | 9 | Bearer 검증과 401 코드 구분, `exp`·`sub` 누락/비정상 토큰 거절 |
| `TokenReissueLogoutTest` | 16 | 재발급 교체, 1회용, 동시 요청, 역할 재계산, 로그아웃 정책 |
| `HomeApiTest` | 8 | 역할별 응답, 권한 검증(401/403/404) |
| `PuppyKindergartenApplicationTests` | 1 | 애플리케이션 컨텍스트 기동 |

**카카오 연동 테스트 방식**
실제 카카오를 호출하지 않고, 테스트 안에서 카카오의 토큰·사용자 정보 엔드포인트를 흉내 내는 가짜 서버(`FakeKakaoServer`)를 띄워 시큐리티 필터 체인 전체를 통과시킨다. 서버가 카카오에 보내는 요청(인가 코드, `client_secret_post`, Bearer 헤더)까지 확인한다.

**보안 관점에서 확인한 항목**

- `state` 가 일치하지 않거나 로그인을 시작한 적 없는 콜백은 거부하고, 카카오 토큰 요청도 보내지 않는다.
- 인가 리다이렉트 URL 에 Client Secret 이 노출되지 않는다.
- 다른 키로 서명한 토큰, 페이로드를 변조한 토큰(견주 → 원장 바꿔치기), 서명 없는 토큰을 거부한다.
- 쿼리 파라미터나 헤더로 역할을 바꿀 수 없다.
- 견주 응답에 원장 전용 필드가, 원장 응답에 견주 전용 필드가 섞여 나가지 않는다.
- 로그인 후에도 세션 쿠키만으로는 보호된 API 에 접근할 수 없다.
- Refresh Token 은 원문이 아니라 해시로만 저장된다.
- 가짜 카카오 서버에 4초 지연을 넣으면 토큰 교환·사용자 조회 모두 제한 시간 안에 503 으로 끝나고 가입되지 않는다.

### 실제 카카오 환경 수동 검증

실제 카카오 개발자 앱과 카카오 계정, 로컬 MySQL 로 아래 흐름을 직접 확인했다. (보완 PR #7 이전 코드 기준)

| 확인 항목 | 결과 |
|---|---|
| 카카오 로그인 → 최초 가입 | 200, `role: OWNER`, MySQL 에 회원 1건과 Refresh Token 해시(64자) 1건 저장 |
| Access Token 클레임 | `sub`, `role=OWNER`, `token_type=access`, 유효 시간 900초 |
| 견주 홈 / 토큰 없음 / Refresh 를 Bearer 로 사용 | 200 견주 응답 / 401 `UNAUTHORIZED` / 401 `INVALID_TOKEN` |
| `DIRECTOR_KAKAO_IDS` 지정 후 재시작 | 기존 Access Token 은 만료 전까지 견주로 동작 |
| 재발급 | 200, Refresh 교체, 새 Access Token `role=DIRECTOR`, 원장 홈 응답 (`todayAttendance: 5`) |
| 사용한 Refresh Token 재사용 | 401 `INVALID_REFRESH_TOKEN`, 교체로 받은 새 토큰은 계속 유효 |
| 로그아웃 | 204, 이후 재발급 401, Access Token 은 만료 전까지 200, 재호출도 204 |

처음 시도에서 카카오 콘솔의 카카오 로그인 사용 설정이 꺼져 있어 KOE004 오류가 났고, 설정을 켠 뒤 위 흐름이 동작했다.

---

## 8. Git 전략

```
main ← epic/auth ← feature/*
```

- `main` 에는 직접 push 하지 않고 `epic/auth` 를 거친 PR 로만 반영한다.
- 기능 단위로 `feature/*` 브랜치를 만들어 `epic/auth` 로 PR 을 보낸다.
- 커밋 메시지는 `feat:`, `fix:`, `docs:`, `chore:` 접두사를 쓴다.

| PR | 브랜치 | 내용 |
|---|---|---|
| #1 | `feature/project-setup` | Spring Boot 3.3 + Gradle 8 뼈대, MySQL docker-compose, 환경변수 구조 |
| #2 | `feature/kakao-login` | 카카오 OAuth2 로그인, 최초 로그인 시 가입, 역할 부여 |
| #3 | `feature/jwt` | 자체 JWT 발급, Bearer 검증 |
| #4 | `feature/token-reissue-logout` | 재발급(교체), 로그아웃(폐기) |
| #5 | `feature/role-home` | 역할 기반 홈 API |
| #6 | `feature/docs` | 문서 |
| #7 | `feature/auth-hardening` | JWT 필수 클레임 검증, 로그인 중 서버 오류 처리, 카카오 타임아웃, 동시 최초 로그인 |

---

## 9. LLM 활용 내역

Claude Code(Anthropic)를 설계, 구현, 테스트, 코드 리뷰, 문서 작성에 사용했다. 이후 Codex(OpenAI)로 전체 코드와 요구사항을 대조하고, 동시 최초 로그인 처리와 README를 보완했다.

### 작업별 활용

| 작업 | 어떻게 썼는가 | 어떻게 검증했는가 |
|---|---|---|
| 설계 | 과제 요구사항을 주고 진행 순서, PR 분할, 설계 선택지를 제안받았다. | 선택지마다 근거를 확인하고 직접 결정했다. 역할 부여 방식은 제안(가입 후 역할 선택 API)을 채택하지 않고 "서버에서 사전 지정" 으로 바꿨고, 로그아웃 정책(Refresh 폐기, Access 는 만료까지 유효)도 직접 정했다. |
| 구현 | 기능 단위로 코드 작성을 맡겼다. | 변경마다 전체 자동 테스트를 실행해 통과를 확인한 뒤 머지했다. 재발급·로그아웃(PR #4)은 변경 내용을 직접 읽고 정책을 다시 확정했다. |
| 테스트 | 정상 흐름과 함께 공격·오류 상황(위조 토큰, state 불일치, 동시 요청 등)의 테스트 작성을 맡겼다. | `./gradlew test` 를 매 변경마다 실행했다. 테스트 이름을 정책 문장으로 써서, 정책과 테스트가 일치하는지 읽어서 확인할 수 있게 했다. |
| 코드 리뷰 | 구현이 끝난 PR 에 LLM 코드 리뷰를 다시 돌렸다. | 나온 항목을 그대로 반영하지 않고 과제 요구사항 기준으로 반영 여부를 판단했다(아래 참고). |
| 문서 | README 초안 작성을 맡겼다. | 코드와 테스트 실행 결과를 기준으로 작성하게 하고, 정책 설명이 실제 동작(테스트)과 일치하는지 대조했다. |

### Codex 추가 리뷰와 보완

- 전체 소스·테스트·README·GitHub PR 상태를 과제 요구사항과 대조하고 기존 테스트 59개 통과를 확인했다.
- 동시 최초 로그인에서 두 요청이 모두 회원이 없다고 조회하면 하나가 중복 삽입 오류로 실패하는 문제를 별도 테스트로 재현했다.
- 실패한 트랜잭션을 롤백한 뒤 새 트랜잭션에서 기존 회원을 조회하도록 수정했다. 두 요청이 같은 회원 ID로 성공하고 DB에는 한 명만 남는지 확인하는 회귀 테스트를 추가했다.
- 중복 가입이 아닌 무결성 오류가 숨겨지지 않는지도 검증했다. 이 변경은 Claude Code 로 다시 검토(트랜잭션 전파, 로그인 실패 처리와의 연결)한 뒤 PR #7 에 포함했고, 전체 테스트 67개가 통과했다. 자동 검증은 H2 기반이며, 실제 MySQL 동시 로그인 검증을 완료했다는 뜻은 아니다.

### LLM 결과를 그대로 쓰지 않은 사례

- **Refresh Token 재사용 감지 제거**: LLM 이 요청하지 않은 기능으로 "폐기된 Refresh Token 이 다시 제시되면 그 사용자의 모든 토큰을 폐기" 를 넣었다. 리뷰에서 네트워크 재시도나 로그아웃 직후의 자동 재발급만으로 다른 기기까지 로그아웃되는 부작용이 드러나 제거하고, "첫 요청 성공 / 이후 요청 401 / 다른 기기 유지" 로 정책을 확정했다.
- **만료된 Bearer 헤더 문제**: 재발급 API 가 "인증 불필요" 로 설정돼 있었지만, 요청에 만료된 Access Token 이 붙어 있으면 필터가 먼저 401 로 거부했다. 테스트가 헤더 없는 경우만 다뤄 통과하고 있었고, 리뷰에서 발견해 수정과 테스트를 추가했다.
- **역할 재계산 누락**: 재발급이 DB 에 저장된 역할을 그대로 다시 담아, 원장 목록에서 제외된 사용자가 재발급만 반복하면 원장 권한을 계속 유지할 수 있었다. 재발급 때도 역할을 다시 계산하도록 고쳤다.
- **만료 허용 오차**: Spring Security 기본값인 60초 허용 오차가 적용돼, 응답으로 안내한 만료 시간(900초)과 실제 동작이 달랐다. 허용 오차를 0 으로 맞췄다.
- **외부 검토 3건 재현 후 반영**: 실제 카카오 검증 뒤 받은 검토 결과(`exp` 없는 토큰 통과, `sub` 오류 시 500, 로그인 중 DB 오류의 응답 형식, 카카오 호출 타임아웃 부재)를 코드와 대조해 모두 사실임을 확인했다. 타임아웃은 검토 당시 재현되지 않았던 부분이라, 가짜 카카오 서버에 지연을 넣어 재현한 뒤 고쳤다.
- **리뷰 항목의 선별 반영**: 두 차례 리뷰에서 나온 19건 중 15건을 반영했다. 나머지는 더미 데이터 범위이거나 의도된 동작(역할 변경은 Access Token 만료 후 반영)이라 반영하지 않고 이 문서에 정책으로 적었다.

### 한계

- 폐기된 Refresh Token의 재사용 시 관련 토큰 계열을 추적·폐기하는 기능은 없다. 탈취한 토큰으로 공격자가 먼저 재발급받으면, 정상 사용자의 이전 토큰 요청은 401이지만 공격자가 받은 새 토큰은 유지된다.
- 카카오 연동의 자동 테스트는 가짜 서버를 상대로 한 것이다. 실제 카카오 환경은 [실제 카카오 환경 수동 검증](#실제-카카오-환경-수동-검증)처럼 직접 확인했지만, 보완 PR #7 이후 코드로는 다시 수동 검증하지 않았다.
- 카카오 지연·장애와 DB 오류는 테스트에서 주입해 재현한 것이고, 실제 장애 상황에서 확인한 것은 아니다.
