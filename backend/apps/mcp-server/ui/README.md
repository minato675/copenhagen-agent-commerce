# Copenhagen MCP UI

## Story 432 독립 쇼핑 카드

`commerce-app.js`와 `commerce.html`을 검색·상세 옵션·장바구니·구매 요청의 네 템플릿으로 빌드한다.
`npm run preview` 실행 후 http://127.0.0.1:4320 에서 시안을 확인한다. 넓은 화면/390px/320px,
빈 결과·이미지 누락/실패·일부 조회 실패를 선택할 수 있다. 버튼은 다음 도구의 카드를 새로 추가한다.
미리보기는 예시 데이터와 테스트 호스트만 사용하며 실제 API·결제·알림은 실행하지 않는다.
질문 예시는 고정 문구다. 실제 질문과 도구 선택은 ChatGPT 모델이 수행한다.

`npm test`, `npm run build`, `npm run check`로 검증한다. 새 `commerce-*.html` 네 파일도 소스와 함께 커밋한다.
배포 후 실제 ChatGPT의 후속 메시지·새 카드 생성·OAuth 권한 동작은 별도 확인이 필요하다.
설계와 Task 범위는 [Story 432 설계](../docs/story-432-design.md)를 참고한다.

## 기존 최소 UI

Story 362 / Task 364. Node.js 22 이상에서 `npm ci`, `npm test`, `npm run build`, `npm run check`를 실행한다.
번들은 `../src/main/resources/mcp-ui/home.html`에 저장하며 Java 빌드에 포함한다. UI 소스를 바꾸면 번들도 함께 커밋한다.
서버가 HTML 내용의 SHA-256으로 리소스 URI를 갱신한다. 기존 채팅 카드는 이전 HTML을 계속 사용할 수 있으므로 새 카드를 열어 확인한다.

ChatGPT Android의 native bridge가 있으면 우선 사용하고, 다른 호스트는 MCP Apps SDK를 사용한다.
화면은 inline만 지원한다. 버튼 응답을 받은 경우에만 갱신 시간과 연결 상태를 표시한다. 호스트가 응답하지 않으면 10초 후 재시도할 수 있다.
도구 메타데이터로 중복 채팅 설명을 줄이지만, 최종 답변 배치는 호스트와 모델이 결정한다.

공식 참고: https://developers.openai.com/plugins/build/chatgpt-ui

## 자산

- `assets/copenhagen-logo.webp`: 사용자가 제공한 Copenhagen 로고의 기존 Lab 무손실 WebP. 색상/형태 변경 없이 사용.
- `fonts/IBMPlexSansKR-*.woff2`: IBM Plex Sans KR 400/600, `@ibm/plex-sans-kr@1.1.0`의 complete WOFF2 원본.
  출처 https://github.com/IBM/plex, OFL-1.1. `fonts/LICENSE.txt`와 HTML 번들 안에 라이선스 포함.
  기존 Lab에서 검증된 파일을 수정 없이 사용한다.
- 외부 이미지·폰트·API 요청 없이 번들 내부 자산과 호스트 도구 호출만 사용한다.
