# 검증 기록

2026-09-29 Windows 개발 PC에서 검증했습니다.

- unittest: 12개 통과 (기존 카메라 오류 처리 8개 + 환경 변수 무인 실행, 키 누락, 예시 값 거절, 수동 입력 분리 4개)
- 실제 Streamable HTTP: 별도 포트 18876에서 도구 탐색, 상태 조회, 잘못된 입력 거절 통과. 촬영하지 않음.
- Bash: setup.sh, run-local.sh, connect-chatgpt.sh, install-service.sh 문법 검사 통과.
- 공식 v0.0.15 ARM64 ZIP: SHA256 일치. 설치 함수의 임시 폴더 압축 해제 검사 통과. ELF64 AArch64 헤더 확인.
- 매뉴얼 PDF: 자동 시작 설치·관리 절차를 포함한 16쪽 전체 렌더링 확인.
- Git 제외 규칙: .env, .env.production, test.env 제외 및 .env.example 포함 확인.
- 개인 키·터널 ID를 배포 파일에 포함하지 않음.

실제 Pi의 apt 설치, USB 촬영, Linux 프로세스 그룹 종료, ARM64 터널 실행 및 모바일 종단 호출은 기기에서 확인해야 합니다. systemd 설치·부팅·네트워크 복구 역시 이 Windows PC에서 실행하지 않았습니다. 테스트 시 재부팅 후 readyz, check.py --capture와 ChatGPT capture_photo까지 확인하세요.
