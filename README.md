# FDS Model Development Environment

UAE E-Commerce Fraud Dataset을 기반으로 결제 이상거래 탐지(FDS) Prototype을 개발하기 위한 프로젝트다. 현재 저장소에는 시간순·Leakage-safe 전처리 파이프라인과 GPU 실행 환경 점검용 smoke test가 포함되어 있다.

전체 실행 절차와 문제 해결 방법은 [상세 사용 매뉴얼](docs/USAGE.md)을 참고한다.

## 실행 환경 구분

이 프로젝트는 Windows 로컬 PC에서 코드를 작성하고 Git으로 원격 SSAFY Jupyter Hub에 전달한 뒤, 실제 전처리·학습·GPU 검증은 Jupyter Hub 서버에서 수행하는 방식으로 운영한다.

### 로컬 개발 PC 점검 결과

이 저장소를 확인한 환경은 원격 Jupyter Hub가 아니라 Windows 개발 PC다.

- Python: 3.11.9
- Git: 2.55.0
- Conda: 미설치
- NVIDIA Driver: 591.44
- CUDA reported by `nvidia-smi`: 13.1
- 현재 PC에서 보이는 GPU: 물리 Device 0, NVIDIA GeForce RTX 4050 Laptop GPU (로컬 테스트용)
- 현재 GPU 사용량: 0 MiB
- Git remote: `origin` 연결 상태이며 자동 commit/push는 수행하지 않는다.

### 원격 Jupyter Hub 실행 환경

Jupyter Hub에는 GPU 10개가 있고, 첨부된 작업 지시문에 따라 이 작업에 할당된 GPU는 물리 장치 `GPU_DEVICE=1`이다. 따라서 GPU 1의 존재 여부와 사용량은 로컬 PC가 아니라 Jupyter Hub에 접속한 터미널에서 확인해야 한다. 로컬 PC의 GPU 0은 원격 작업의 GPU 번호와 무관하다.

원격 서버에서만 다음과 같이 실행한다.

```bash
nvidia-smi
export GPU_DEVICE=1
CUDA_DEVICE_ORDER=PCI_BUS_ID CUDA_VISIBLE_DEVICES="$GPU_DEVICE" python scripts/check_gpu.py
GPU_DEVICE=1 bash scripts/train.sh --epochs 1 --batch-size 32
```

`CUDA_VISIBLE_DEVICES=1`로 물리 GPU 1개만 프로세스에 노출하면, 해당 프로세스 내부에서는 그 장치가 `cuda:0`으로 표시된다. 이는 정상 동작이다. Python 코드에는 물리 GPU 번호를 하드코딩하지 않는다.

## 로컬 → 원격 작업 흐름

1. 로컬에서 소스·설정·스크립트를 수정하고 테스트한다.
2. Git으로 원격 저장소에 반영한다.
3. Jupyter Hub에서 저장소를 clone/pull하고, 원격 서버의 Conda 환경을 구성한다.
4. 원격 서버에 별도로 준비된 원본 데이터를 `data/raw/` 또는 기존 전처리 입력 위치에 둔다.
5. Jupyter Hub에서 `GPU_DEVICE=1`을 지정해 GPU 점검과 학습을 실행한다.

원본 CSV, 전처리 결과, 로그, 체크포인트는 `.gitignore`로 제외되어 Git을 통해 자동 전송되지 않는다. 원본 데이터는 서버에 별도로 업로드하거나 서버의 공유 저장소에서 연결해야 한다.

## 프로젝트 구조

```text
.
├── README.md
├── .gitignore
├── environment.yml
├── requirements.txt
├── configs/
├── notebooks/
├── scripts/
│   ├── check_gpu.py
│   ├── env_info.sh
│   └── train.sh
├── src/
│   ├── train.py
│   └── preprocessing/
├── tests/
├── data/
│   ├── raw/             # 원본 데이터, Git 제외
│   ├── processed/       # 생성 CSV, Git 제외
│   └── reports/         # Markdown 보고서만 추적
├── outputs/             # 실험 산출물, Git 제외
├── checkpoints/         # 모델 체크포인트, Git 제외
└── logs/                # 실행 로그, Git 제외
```

기존 `src/preprocessing` 구조와 전처리 산출물은 유지했다. 원본 CSV와 생성 CSV는 `.gitignore`에 의해 Git에 올라가지 않는다.

## Conda 환경 구성

원격 Jupyter Hub 서버에서 Conda가 제공되는 경우:

```bash
conda env create -f environment.yml
conda activate fds-model
```

또는 기존 Conda 환경 안에서:

```bash
python -m pip install -r requirements.txt
```

PyTorch는 원격 서버의 `nvidia-smi` 결과와 NVIDIA Driver 호환성을 먼저 확인한 후 설치한다. 시스템 Python이나 `sudo pip`에는 패키지를 설치하지 않는다. 로컬 Windows에서 Conda가 없다는 점은 원격 환경 구성과 별개다.

## GPU 선택 원칙

물리 GPU 번호는 Python 코드에 하드코딩하지 않는다. 실행 시 환경변수로 전달한다.

원격 Jupyter Hub에서 할당된 GPU가 1인 경우:

```bash
export GPU_DEVICE=1
nvidia-smi
CUDA_DEVICE_ORDER=PCI_BUS_ID CUDA_VISIBLE_DEVICES="$GPU_DEVICE" python scripts/check_gpu.py
GPU_DEVICE=1 bash scripts/train.sh --epochs 1 --batch-size 32
```

첫 번째 인자로도 지정할 수 있다.

```bash
bash scripts/train.sh 1 --epochs 1 --batch-size 32
```

`CUDA_VISIBLE_DEVICES=1`로 물리 GPU 1개만 노출하면 해당 프로세스 내부에서는 그 GPU가 `cuda:0`으로 보인다. 이는 정상 동작이며 Python 코드에서 물리 번호를 직접 지정하지 않는 이유다.

로컬 Windows PC에서 GPU smoke test를 수행해야 하는 경우에만 로컬 물리 GPU 0을 사용한다. 이 명령은 원격 Jupyter Hub 학습을 대신하지 않는다.

```powershell
$env:CUDA_DEVICE_ORDER = "PCI_BUS_ID"
$env:CUDA_VISIBLE_DEVICES = "0"
python scripts/check_gpu.py
python src/train.py --epochs 1 --batch-size 32
```

## 스크립트

### GPU 점검

```bash
CUDA_DEVICE_ORDER=PCI_BUS_ID CUDA_VISIBLE_DEVICES="$GPU_DEVICE" python scripts/check_gpu.py
```

PyTorch 버전, CUDA 사용 가능 여부, Torch CUDA 버전, visible GPU 수/이름, 현재 device, CUDA Tensor 연산 및 메모리 할당 결과를 출력한다. PyTorch가 없거나 CUDA를 사용할 수 없는 경우에도 원인을 출력하고 비정상 종료하지 않는다.

### 학습 진입점 smoke test

`src/train.py`는 실제 FDS 학습 모델이 아니라 GPU/CPU 실행 검증용 최소 진입점이다. `argparse`, seed 42, CUDA 확인, Tensor 연산, 메모리 할당, CPU fallback을 포함한다.

### 환경 정보

Linux 서버에서 다음 명령으로 민감한 credential 값을 제외한 환경 정보를 확인한다.

```bash
bash scripts/env_info.sh
```

### 전처리

```bash
python -m src.preprocessing.run_preprocessing --project-root .
```

전처리 결과는 `data/processed/`에 생성되며 Git에서는 제외된다. 자세한 데이터 설명은 [`data/dataset_description.md`](data/dataset_description.md), 실행 보고서는 [`data/reports/preprocessing_report.md`](data/reports/preprocessing_report.md)를 참고한다.

## Checkpoint 및 세션 제한

Jupyter Hub 세션은 약 24시간 후 종료될 수 있으므로 장시간 학습은 `checkpoints/`에 주기적으로 저장하도록 설계한다. PyTorch checkpoint에는 다음 항목을 포함하는 것을 권장한다.

- `epoch`
- `model_state_dict`
- `optimizer_state_dict`
- `scheduler_state_dict`
- `scaler_state_dict` (AMP 사용 시)
- `best_metric`
- 학습 configuration

재시작 시 checkpoint를 불러와 `resume` 학습할 수 있도록 학습 코드를 확장한다. 현재 `src/train.py`는 smoke test만 수행하므로 실제 모델 checkpoint 저장은 아직 구현하지 않았다.

## Git 주의사항

- `data/`, `outputs/`, `checkpoints/`, `logs/`의 대용량 산출물은 Git에 포함하지 않는다.
- `.env`, token, API key, SSH key, 서버 인증정보를 저장하지 않는다.
- 현재 저장소의 변경사항은 `git status`로 확인한다.
- 자동 commit, push, remote 생성은 수행하지 않는다.
