package com.copenhagen.merchantmock.support;

/**
 * 오류 응답 본문. 팀 API 명세 v4 {@code 공통 오류 응답} 시트가 정한 네 필드다(DESIGN.md 10절).
 *
 * <p>2026-09-14 까지는 {@code {code, message, requestId}} 세 필드였다. 명세가 네 필드로 정리된
 * 것을 뒤늦게 확인해 맞췄다(S15P21A207-286). Pay 의 {@code ApiErrorResponse} 도 같은 넷이다.
 *
 * <p>네 키를 항상 내보낸다. 값이 없어도 키를 지우지 않는다. Core 가 키 존재를 전제로 파싱할 수
 * 있어서다. Pay 도 같은 방식이다(15.4절).
 *
 * @param errorCode   {@code 도메인_사유} 형태. 코드 목록은 DESIGN.md 10절이 소유한다
 * @param message     사람이 읽는 설명. 민감정보를 담지 않는다
 * @param currentState 오류 시점의 리소스 상태. 소문자 snake. 상태가 없는 오류는 null
 * @param nextAction  사용자가 다음에 할 일. 설계서 16.7 의 안내 매핑이 이 값을 읽는다
 */
public record ErrorResponse(String errorCode, String message, String currentState, String nextAction) {
}
