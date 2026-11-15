package com.puppykindergarten.config;

/**
 * 모든 에러 응답의 본문 형식. 필터 단계와 컨트롤러 단계가 함께 쓴다.
 */
public record ApiError(String code, String message) {
}
