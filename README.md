# puppy-kindergarten-api

반려견 유치원(견주 ↔ 원장 연결 플랫폼) 인증/권한 API.

Kakao 소셜 로그인(OAuth 2.0) → 자체 JWT 발급/검증 → 역할(원장/견주) 기반 홈 분기.

## 브랜치 전략

```
main ← epic/auth ← feature/*
```

- `main`에는 직접 push하지 않고, `epic/auth`를 거친 PR로만 반영한다.
- 기능 단위로 `feature/*` 브랜치를 만들어 `epic/auth`로 PR을 보낸다.
- 커밋 메시지는 `feat:`, `fix:`, `docs:`, `chore:`, `test:` 접두사를 쓴다.

상세 문서(흐름도, 기술 선택 근거, 실행 방법, API 명세, LLM 활용 내역)는 구현과 함께 추가한다.
