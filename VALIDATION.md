# 검증 기록

2026-09-29 Windows 개발 PC에서 검증했습니다.

- unittest: 8개 통과 (동시 촬영 거절, 시간 초과, 취소 정리, 잘못된 JPEG, 키 격리, USB 장치 해제, CSI 명령 및 실행 파일 누락)
- 실제 Streamable HTTP: 별도 포트 18876에서 도구 탐색, 상태 조회, 잘못된 입력 거절 통과. 촬영하지 않음.
- Bash: setup.sh, run-local.sh, connect-chatgpt.sh 문법 검사 통과. LF 줄바꿈 확인.
- 공식 v0.0.15 ARM64 ZIP: SHA256 일치. 설치 함수의 임시 폴더 압축 해제 검사 통과. ELF64 AArch64 헤더 확인.
- 매뉴얼 PDF: 14쪽 전체 렌더링 확인.
- 개인 키·터널 ID를 배포 파일에 포함하지 않음.

실제 Pi의 apt 설치, USB 촬영, Linux 프로세스 그룹 종료, ARM64 터널 실행 및 모바일 종단 호출은 기기에서 확인해야 합니다. 테스트 시 check.py --capture와 ChatGPT capture_photo까지 실행하세요.
