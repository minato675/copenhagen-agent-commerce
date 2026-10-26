# Raspberry Pi Camera MCP — Copenhagen 추가 도구

저는 라즈베리파이 카메라 MCP를 코펜하겐의 추가 도구로 정리했습니다.
USB 웹캠 또는 CSI 카메라로 사진 한 장을 촬영해 ChatGPT에서 분석할 수 있도록 연결하는 코드입니다.

## 이 저장소에서 설치

```bash
git clone https://github.com/minato675/copenhagen-agent-commerce.git
cd copenhagen-agent-commerce/integrations/raspberrypi-camera-mcp
bash setup.sh usb
bash connect-chatgpt.sh usb
```

공개 저장소를 내려받을 수 있습니다. Raspberry Pi OS 64비트와 장치 설정을 먼저 준비합니다.
CSI 모듈을 사용할 때는 각 실행기의 `csi` 옵션을 사용합니다.

이 도구는 별도 프로세스로 실행합니다. 이번 통합으로 구매 API나 Core·Pay와의 자동 연동을 추가하지 않았습니다.
기존 검증은 Windows 개발 PC의 코드·오류 처리·MCP 통신 범위이며, 실제 Raspberry Pi 설치·촬영·ARM64 터널은 장치 검증이 필요합니다.

[설치·자동 시작·문제 해결 원본 매뉴얼](../../docs/repository-merges/ssafy207/README.original.md)은 이관 전 저장소 이름을 사용합니다.
원본 매뉴얼의 기능·검증 범위를 유지하되, 저장소를 내려받을 때는 위의 Copenhagen 경로를 사용합니다.
원본 커밋은 `history/ssafy207/master`에 보존했습니다.
