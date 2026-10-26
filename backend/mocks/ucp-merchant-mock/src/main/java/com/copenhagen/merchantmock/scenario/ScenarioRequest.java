package com.copenhagen.merchantmock.scenario;

/**
 * 시나리오 변경 요청 본문. 이름은 문자열로 받아 컨트롤러가 검증한다.
 * enum 으로 바로 받으면 모르는 이름이 Spring 기본 400 으로 나가 오류 Envelope 모양이 깨진다.
 */
public record ScenarioRequest(String scenario) {
}
