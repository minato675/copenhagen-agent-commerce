# FDS Model 사용 매뉴얼

이 문서는 uae_ecom_fraud_100k.csv를 이용해 FDS 전처리 파이프라인을 실행하고, Git을 통해 로컬 Windows 개발 환경의 변경사항을 원격 SSAFY Jupyter Hub GPU 서버로 전달하는 전체 절차를 설명한다.

현재 저장소의 src/train.py는 실제 Fraud Detection 모델 학습 코드가 아니라 실행 환경을 확인하는 GPU/CPU smoke test다. 전처리와 실행 환경을 검증한 뒤 실제 모델 학습 루프를 추가하는 것을 다음 개발 단계로 한다.

## 1. 작업 환경의 역할

이 프로젝트는 다음처럼 두 환경을 분리해서 사용한다.

| 환경 | 주요 역할 | GPU 사용 |
|---|---|---|
| 로컬 Windows PC | 소스 수정, 문서 작성, 단위 테스트, Git commit/push | 원격 학습에는 사용하지 않음 |
| SSAFY Jupyter Hub 원격 Linux 서버 | 데이터 준비, Conda 환경 구성, 전처리, GPU 점검, 모델 학습 | 할당된 물리 GPU 1 사용 |

Jupyter Hub에는 GPU가 10개 있으며, 이 작업의 지시된 장치는 GPU_DEVICE=1이다. 로컬 PC에서 보이는 GPU 0과 원격 서버의 GPU 1은 서로 다른 장치다. 따라서 원격 GPU의 상태는 반드시 Jupyter Hub 터미널에서 확인한다.

## 2. 프로젝트 구조

~~~text
model/
├── README.md                         # 프로젝트 개요와 빠른 시작
├── .gitignore                        # 데이터·산출물·비밀정보 제외 규칙
├── environment.yml                   # Conda Python 3.11 환경
├── requirements.txt                  # pip 설치 목록
├── docs/
│   └── USAGE.md                      # 이 상세 사용 매뉴얼
├── configs/                          # 향후 모델 설정 파일
├── notebooks/                        # 탐색·분석용 Jupyter Notebook
├── scripts/
│   ├── check_gpu.py                  # PyTorch/CUDA 점검
│   ├── env_info.sh                   # 서버 환경 정보 점검
│   └── train.sh                      # GPU 선택·로그·train.py 실행 래퍼
├── src/
│   ├── train.py                      # 현재는 GPU smoke-test 진입점
│   └── preprocessing/                # FDS 전처리·Feature Engineering
├── tests/                            # 전처리 단위 테스트
├── data/
│   ├── raw/                          # 원본 데이터, Git 제외
│   ├── processed/                    # 생성 데이터, Git 제외
│   └── reports/                      # Markdown 실행 보고서, Git 추적
├── outputs/                          # 예측·평가 결과, Git 제외
├── checkpoints/                      # 모델 checkpoint, Git 제외
└── logs/                             # 실행 로그, Git 제외
~~~

원본 CSV와 생성 CSV는 .gitignore에 의해 Git에 포함되지 않는다. 소스와 설정만 Git으로 보내고, 데이터는 원격 서버에 별도로 업로드하거나 공유 스토리지에서 연결한다.

## 3. 전체 실행 순서

처음 실행할 때는 다음 순서를 지킨다.

1. 로컬에서 코드를 수정하고 단위 테스트를 실행한다.
2. 변경된 소스·설정·문서를 Git에 commit하고 원격 저장소에 push한다.
3. Jupyter Hub에서 해당 브랜치를 pull한다.
4. 원격 서버에 원본 CSV를 data/raw/에 준비한다.
5. 원격 서버에서 Conda 환경을 만들고 활성화한다.
6. GPU_DEVICE=1로 GPU를 점검한다.
7. 전처리 파이프라인을 실행한다.
8. 생성된 보고서와 CSV를 확인한다.
9. smoke test 또는 실제 학습 진입점을 실행한다.

## 4. 로컬 Windows에서 작업하기

### 4.1 저장소 상태 확인

PowerShell에서 프로젝트 루트로 이동한다.

~~~powershell
Set-Location "C:\Users\SSAFY\Desktop\특화\model"
git status --short --branch
git remote -v
~~~

작업 중인 파일이 있는데 원격 변경사항을 가져오려면 먼저 변경 내용을 저장하거나 별도 백업한다. 무심코 git reset --hard나 파일 삭제 명령을 실행하지 않는다.

### 4.2 로컬 테스트

현재 로컬에는 Python 3.11.9가 있지만 Conda와 PyTorch는 설치되어 있지 않다. 따라서 전처리 테스트와 Python 문법 검사는 실행할 수 있고, CUDA smoke test는 원격 환경에서 실행해야 한다.

~~~powershell
python --version
python -m compileall -q src scripts tests
python -m unittest discover -s tests -v
~~~

단위 테스트가 OK로 끝나면 전처리 코드의 기본 동작을 확인한 것이다. 테스트는 임시 테스트 데이터를 사용하며, 실제 100,000행 데이터의 결과 파일을 Git에 추가하지 않는다.

### 4.3 Git으로 변경사항 전달하기

commit 전에 스테이징 목록을 반드시 확인한다. 다음 명령은 코드·설정·문서와 디렉터리 placeholder만 추가하며, .gitignore에 지정된 raw/processed 데이터와 로그는 제외한다.

~~~powershell
git add .gitignore README.md environment.yml requirements.txt docs scripts src tests configs notebooks data/raw data/reports outputs checkpoints logs
git diff --cached --name-only
git status
~~~

스테이징 목록에 *.csv, data/processed/의 생성 파일, outputs/ 산출물, token 또는 인증서가 보이면 commit하지 않는다. 프로젝트의 브랜치·Jira 규칙을 확인한 뒤 승인된 작업 브랜치에서 commit/push한다.

~~~powershell
git commit -m "docs: 사용 방법 매뉴얼 작성"
git push origin <승인된-작업브랜치>
~~~

현재 저장소에 자동 commit/push는 수행하지 않는다. 브랜치 이름이나 Jira 이슈 키가 필요한 경우 임의의 값을 만들지 말고 팀에서 지정한 값을 사용한다.

## 5. Jupyter Hub에서 저장소 받기

### 5.1 최초 clone

Jupyter Hub 터미널에서 저장소가 아직 없다면 저장소 URL과 사용할 브랜치를 지정해 clone한다.

~~~bash
cd ~
git clone <저장소-URL> model
cd model
git switch <승인된-작업브랜치>
~~~

이미 clone한 상태라면 프로젝트 루트에서 상태를 확인한 후 최신 변경을 받는다.

~~~bash
cd ~/model
git status --short --branch
git pull --ff-only origin <승인된-작업브랜치>
~~~

로컬 변경사항이 남아 있으면 pull 전에 그 변경사항을 보존한다. 원격 서버에서 직접 수정한 파일이 있다면 로컬 변경과 덮어쓰지 않도록 먼저 commit하거나 팀과 조정한다.

### 5.2 프로젝트 루트 확인

모든 상대 경로 명령은 프로젝트 루트에서 실행한다.

~~~bash
pwd
git rev-parse --show-toplevel
ls -la
~~~

README.md, src/, scripts/, environment.yml이 보이면 올바른 위치다.

## 6. 원본 데이터 준비

### 6.1 권장 위치

uae_ecom_fraud_100k.csv를 원격 서버의 다음 위치에 둔다.

~~~text
<project-root>/data/raw/uae_ecom_fraud_100k.csv
~~~

예시:

~~~bash
mkdir -p data/raw
# 별도 업로드·공유 스토리지에서 다음 위치로 파일을 준비한다.
ls -lh data/raw/uae_ecom_fraud_100k.csv
~~~

현재 파이프라인은 data/raw/*.csv를 우선 탐색한다. data/raw/에 CSV가 없을 때만 프로젝트 루트의 CSV를 fallback으로 탐색한다. 여러 CSV가 있으면 --input-csv로 대상 파일을 명시하는 것이 안전하다.

### 6.2 입력 파일의 최소 확인

환경을 활성화한 뒤 전체 파일을 불필요하게 읽지 않고 헤더와 일부 행만 확인한다.

~~~bash
python - <<'PY'
from pathlib import Path
import pandas as pd

path = Path("data/raw/uae_ecom_fraud_100k.csv")
if not path.exists():
    raise SystemExit(f"입력 파일이 없습니다: {path}")

sample = pd.read_csv(path, nrows=5)
required = {"is_fraud", "transaction_id"}
missing = sorted(required - set(sample.columns))
print("shape(sample):", sample.shape)
print("columns:", sample.columns.tolist())
if missing:
    raise SystemExit(f"필수 컬럼 누락: {missing}")
print("target sample:", sample["is_fraud"].tolist())
PY
~~~

is_fraud는 반드시 명시적인 0/1 정답 컬럼이어야 한다. event, transactionid 등의 존재만으로 사기 라벨을 추정하지 않는다.

### 6.3 데이터와 Git의 관계

- 원본 데이터는 Git에서 제외한다.
- 전처리 CSV와 모델 산출물도 Git에서 제외한다.
- data/dataset_description.md와 data/reports/*.md는 문서이므로 추적 대상이다.
- 원격 서버에 데이터가 없으면 Git pull만으로는 학습을 시작할 수 없다.
- 데이터 업로드 경로와 접근 권한은 Jupyter Hub/팀 운영 정책을 따른다.

## 7. 원격 Conda 환경 구성

### 7.1 서버 기본 환경 확인

첨부 지시문에 따라 시스템 Python이나 sudo pip를 사용하지 않는다.

~~~bash
command -v conda
conda --version
python --version
python -m pip --version
nvidia-smi
~~~

Conda가 정상적으로 보이면 프로젝트 환경을 만든다.

### 7.2 처음 만드는 경우

~~~bash
cd <project-root>
conda env create -f environment.yml
conda activate fds-model
~~~

environment.yml은 Python 3.11, NumPy, pandas, scikit-learn, matplotlib, joblib, PyTorch를 정의한다.

### 7.3 환경이 이미 있는 경우

~~~bash
conda activate fds-model
conda env update -f environment.yml
~~~

환경을 만들거나 업데이트한 뒤 실제 활성 환경을 확인한다.

~~~bash
which python
python --version
python -m pip --version
~~~

which python이 fds-model 환경의 경로를 가리켜야 한다. conda activate가 동작하지 않으면 서버의 Conda 초기화 방법 또는 운영자 안내를 따르며, 시스템 영역에 직접 설치하지 않는다.

### 7.4 PyTorch 설치와 호환성

현재 설정은 torch>=2.5,<3을 요청한다. GPU 서버의 NVIDIA Driver와 PyTorch CUDA build 호환성을 먼저 확인한다.

~~~bash
nvidia-smi
python -m pip install -r requirements.txt
~~~

이미 environment.yml로 설치했다면 같은 환경에 불필요하게 여러 PyTorch build를 중복 설치하지 않는다. 설치 후에는 반드시 다음 점검을 실행한다.

~~~bash
python - <<'PY'
import torch
print("torch:", torch.__version__)
print("torch CUDA build:", torch.version.cuda)
print("CUDA available:", torch.cuda.is_available())
print("visible GPU count:", torch.cuda.device_count())
if torch.cuda.is_available():
    for index in range(torch.cuda.device_count()):
        print(index, torch.cuda.get_device_name(index))
PY
~~~

nvidia-smi가 정상이어도 CUDA available: False일 수 있다. 이 경우 PyTorch build, 활성 Conda 환경, CUDA_VISIBLE_DEVICES, Driver 호환성을 순서대로 확인한다.

## 8. GPU 선택과 점검

### 8.1 할당 GPU 확인

원격 Jupyter Hub에서만 물리 GPU 1을 지정한다.

~~~bash
export GPU_DEVICE=1
nvidia-smi -i "$GPU_DEVICE"
~~~

다른 사용자의 GPU를 점유하지 않도록 할당받은 장치만 사용한다. GPU 사용량과 프로세스가 이미 점유되어 있으면 임의로 종료하지 말고 서버 운영 정책을 따른다.

### 8.2 GPU 점검 스크립트

~~~bash
export CUDA_DEVICE_ORDER=PCI_BUS_ID
export CUDA_VISIBLE_DEVICES="$GPU_DEVICE"
python scripts/check_gpu.py
~~~

정상적인 주요 출력은 다음을 포함한다.

- PyTorch 버전
- PyTorch가 build된 CUDA 버전
- CUDA available: True
- 프로세스에서 보이는 GPU 수와 이름
- 현재 device
- 작은 CUDA Tensor 연산 성공 여부
- 약 4 MiB 메모리 할당 성공 여부

물리 GPU 1을 CUDA_VISIBLE_DEVICES=1로 노출하면 Python 프로세스 안에서는 그 장치가 cuda:0으로 표시된다. 이는 장치를 잘못 선택한 것이 아니다. Python 코드에서 torch.device("cuda:1") 또는 torch.cuda.set_device(1)을 하드코딩하지 않는다.

### 8.3 환경 정보 스크립트

credential 값은 출력하지 않고 서버·Python·Conda·PyTorch·GPU·CPU·RAM·디스크 상태를 확인한다.

~~~bash
CUDA_VISIBLE_DEVICES="$GPU_DEVICE" bash scripts/env_info.sh
~~~

스크립트가 출력한 내용 중 경로, GPU 이름, 사용량, 메모리, 디스크 여유 공간을 확인한다. API token, 비밀번호, SSH key는 명령어 또는 로그에 포함하지 않는다.

## 9. 전처리 파이프라인 실행

### 9.1 기본 실행

프로젝트 루트와 Conda 환경이 활성화된 상태에서 실행한다.

~~~bash
cd <project-root>
conda activate fds-model
python -m src.preprocessing.run_preprocessing --project-root .
~~~

입력 탐색 순서는 다음과 같다.

1. data/raw/*.csv
2. data/raw/에 CSV가 없을 때 프로젝트 루트 *.csv
3. --input-csv가 지정되면 지정한 파일 하나

### 9.2 권장: 입력 파일 명시 실행

~~~bash
python -m src.preprocessing.run_preprocessing \
  --project-root . \
  --input-csv data/raw/uae_ecom_fraud_100k.csv \
  --verbose
~~~

입력 파일을 명시하면 여러 CSV가 서버에 있어도 잘못된 파일이 선택될 가능성을 줄일 수 있다.

### 9.3 CLI 옵션

| 옵션 | 기본값 | 설명 |
|---|---|---|
| --project-root PATH | 현재 디렉터리 | 프로젝트 기준 경로. 출력도 이 경로 아래 생성 |
| --input-csv PATH | 자동 탐색 | 처리할 CSV를 직접 지정 |
| --aed-to-krw FLOAT | 370.0 | AED 금액의 Prototype 환산 계수 |
| --no-encode-categoricals | 미사용 | 범주형 One-Hot 인코딩을 끄는 진단용 옵션 |
| --allow-missing-target | 미사용 | is_fraud가 없을 때 report-only 실행 허용 |
| --verbose | 미사용 | 상세 로그 출력 |

환율은 실시간 환율이 아니라 Prototype 기본값이다. 실험에서 다른 값을 사용했다면 실행 명령과 보고서에 그 값을 남긴다.

예시:

~~~bash
python -m src.preprocessing.run_preprocessing \
  --project-root . \
  --input-csv data/raw/uae_ecom_fraud_100k.csv \
  --aed-to-krw 370.0 \
  --verbose
~~~

### 9.4 전처리 단계에서 수행하는 작업

파이프라인은 대략 다음 순서로 동작한다.

1. CSV를 탐색하고 대상 파일을 선택한다.
2. 스키마, 결측치, 중복, target 분포를 점검한다.
3. timestamp 기준으로 정렬하고 결측·자료형을 정리한다.
4. amount_krw, 사용자 과거 통계, 금액 비율·편차·z-score를 만든다.
5. 거래 속도와 시간·지역 관계형 Feature를 만든다.
6. 정답 생성 규칙으로 보이는 fraud_flag_* Leakage 컬럼을 제거한다.
7. 사용자 통계 계산 후 transaction_id, user_id, ip_address 같은 직접 식별자를 모델 입력에서 제거한다.
8. 저카디널리티 범주형 컬럼을 One-Hot 인코딩한다.
9. 출력 데이터와 Feature 요약을 저장한다.
10. 행 수, 정렬, target, 숫자 유한값, Leakage·식별자 제거 여부를 검증한다.

### 9.5 target이 없는 파일을 넣은 경우

is_fraud가 없는 CSV에서 사기 라벨을 임의로 만들지 않는다.

~~~bash
python -m src.preprocessing.run_preprocessing \
  --project-root . \
  --input-csv data/raw/other.csv
~~~

이 경우 진단 보고서만 작성되고 모델용 CSV는 생성되지 않으며, 일반적으로 종료 코드 2가 반환된다. 스키마 확인만 허용하려면 다음을 사용한다.

~~~bash
python -m src.preprocessing.run_preprocessing \
  --project-root . \
  --input-csv data/raw/other.csv \
  --allow-missing-target
~~~

--allow-missing-target은 target을 추정하거나 생성하는 옵션이 아니라 report-only 예외 처리다.

### 9.6 종료 코드

| 종료 코드 | 의미 | 조치 |
|---:|---|---|
| 0 | 전처리 완료 또는 허용된 report-only 완료 | 산출물과 보고서 확인 |
| 1 | 예외로 인해 실행 실패 | 로그·traceback·입력 경로 확인 |
| 2 | target 누락 또는 최종 검증 실패 | 입력 스키마와 보고서 확인 |

## 10. 산출물 확인

기본 출력은 다음과 같다.

| 경로 | 내용 |
|---|---|
| data/processed/fraud_processed.csv | 원본 보존 컬럼과 파생 Feature를 포함한 처리 데이터 |
| data/processed/fraud_model_features.csv | 모델 입력 Feature와 is_fraud target |
| data/processed/feature_summary.csv | Feature별 타입·결측·고유값·통계 요약 |
| data/reports/preprocessing_report.md | 실행 상태, 경고, 제거 컬럼, 검증 결과 |
| data/processed/fraud_feature_encoder.joblib | scikit-learn과 joblib 사용 시 재사용용 encoder |

파일과 보고서를 확인한다.

~~~bash
ls -lh data/processed data/reports
head -n 5 data/processed/fraud_model_features.csv
sed -n '1,220p' data/reports/preprocessing_report.md
~~~

현재 제공된 uae_ecom_fraud_100k.csv 기준 예상 결과는 다음과 같다. 다른 데이터나 옵션을 사용하면 크기가 달라질 수 있다.

- 입력: 100,000행 × 36열
- fraud_processed.csv: 100,000행 × 59열
- fraud_model_features.csv: 100,000행 × 78열 (Feature 77개 + is_fraud)
- feature_summary.csv: 78행
- is_fraud=0: 91,789건
- is_fraud=1: 8,211건

모델 Feature CSV에 다음이 남아 있지 않은지 확인한다.

~~~bash
python - <<'PY'
import pandas as pd

path = "data/processed/fraud_model_features.csv"
frame = pd.read_csv(path)
leakage = [column for column in frame.columns if "fraud_flag" in column.lower()]
identifiers = [column for column in ("transaction_id", "user_id", "ip_address") if column in frame.columns]
print("shape:", frame.shape)
print("target counts:", frame["is_fraud"].value_counts().to_dict())
print("leakage columns:", leakage)
print("identifier columns:", identifiers)
print("all numeric:", all(pd.api.types.is_numeric_dtype(frame[column]) for column in frame.columns))
PY
~~~

## 11. GPU smoke test와 학습 진입점

### 11.1 src/train.py 직접 실행

현재 파일은 실제 모델을 학습하지 않고 PyTorch 실행 환경만 확인한다.

~~~bash
CUDA_DEVICE_ORDER=PCI_BUS_ID \
CUDA_VISIBLE_DEVICES=1 \
python -u src/train.py --epochs 1 --batch-size 32 --seed 42
~~~

정상적으로 PyTorch CUDA가 연결되면 GPU 이름, Tensor 연산, 약 4 MiB 메모리 할당 성공 메시지가 출력된다. CUDA가 unavailable이면 CPU fallback을 사용한다.

### 11.2 scripts/train.sh 사용

train.sh는 물리 GPU 번호를 받아 CUDA_VISIBLE_DEVICES를 설정하고, src/train.py를 실행하며 로그를 logs/에 저장한다.

환경변수 방식:

~~~bash
export GPU_DEVICE=1
bash scripts/train.sh --epochs 1 --batch-size 32
~~~

첫 번째 인자 방식:

~~~bash
bash scripts/train.sh 1 --epochs 1 --batch-size 32
~~~

첫 번째 인자가 숫자가 아니고 GPU_DEVICE도 없으면 안전을 위해 실행하지 않고 종료한다. 로그 파일 이름은 다음 형식이다.

~~~text
logs/train_YYYYMMDD_HHMMSS_gpu1.log
~~~

현재 smoke test에는 실제 epoch 반복, train/validation split, 평가 지표, 모델 저장이 없다. 따라서 이 명령이 성공해도 Fraud Detection 모델 학습이 완료된 것은 아니다.

## 12. 실제 모델 학습을 추가할 때의 기준

실제 FDS 모델을 구현할 때는 전처리 결과를 그대로 무작위 분할하지 말고 timestamp 기준으로 과거를 Train, 미래를 Validation/Test로 분리한다.

권장 사항:

- 불균형 지표: PR-AUC, Recall, Precision, F1, 비용 기반 지표를 함께 사용한다.
- 동일 사용자의 미래 정보가 과거 Feature에 섞이지 않도록 prior-only 계산을 유지한다.
- threshold는 운영 비용과 탐지율을 기준으로 별도 조정한다.
- 학습·검증·테스트 기간과 Feature 생성 시점을 로그에 남긴다.
- 모델 입력에는 직접 식별자와 정답 생성 규칙 컬럼을 넣지 않는다.
- 실험 설정과 seed를 기록한다.

현재 src/train.py는 smoke test이므로 --resume 옵션과 checkpoint 저장이 구현되어 있지 않다. 실제 학습 루프를 추가할 때 다음 정보를 checkpoint에 저장한다.

~~~python
checkpoint = {
    "epoch": epoch,
    "model_state_dict": model.state_dict(),
    "optimizer_state_dict": optimizer.state_dict(),
    "scheduler_state_dict": scheduler.state_dict(),
    "scaler_state_dict": scaler.state_dict(),  # AMP 사용 시
    "best_metric": best_metric,
    "config": config,
}
~~~

저장 위치는 checkpoints/last.pt, checkpoints/best.pt처럼 구분하고, 재시작 시 현재 device가 아닌 CPU에서도 읽을 수 있도록 map_location=device를 고려한다. checkpoint는 Git에 commit하지 않는다.

## 13. 자주 발생하는 문제

### conda: command not found

현재 명령이 Jupyter Hub가 아닌 로컬 Windows에서 실행되고 있거나 Conda 초기화가 되지 않은 경우다. 서버의 Conda 사용 안내를 확인하고, 시스템 전역 설치나 sudo를 사용하지 않는다.

### No CSV files found

프로젝트 루트가 맞는지 확인하고, 파일을 data/raw/에 준비했는지 확인한다.

~~~bash
pwd
ls -lh data/raw
python -m src.preprocessing.run_preprocessing --project-root . --input-csv data/raw/uae_ecom_fraud_100k.csv
~~~

### is_fraud가 없다는 보고서가 생성됨

잘못된 CSV가 선택된 것이다. --input-csv로 uae_ecom_fraud_100k.csv를 명시하고 헤더에 is_fraud가 있는지 확인한다. 이벤트의 transaction 값만으로 label을 만들지 않는다.

### CUDA available: False

다음 순서로 확인한다.

~~~bash
conda activate fds-model
nvidia-smi -i 1
echo "$CUDA_VISIBLE_DEVICES"
python -c "import torch; print(torch.__version__, torch.version.cuda, torch.cuda.is_available())"
python scripts/check_gpu.py
~~~

PyTorch가 CPU build로 설치되었거나 Driver와 build가 호환되지 않을 수 있다. 서버의 CUDA/Driver 정책에 맞는 PyTorch build를 사용한다.

### invalid device ordinal 또는 GPU 1을 찾지 못함

현재 세션이 GPU 1을 할당받았는지 nvidia-smi와 서버 스케줄러 정보를 확인한다. CUDA_VISIBLE_DEVICES로 한 장치만 노출된 프로세스 내부에서는 장치 번호가 cuda:0이므로 Python에서 cuda:1을 지정하지 않는다.

### ModuleNotFoundError: torch 또는 기타 패키지 누락

Conda 환경이 활성화되었는지 확인하고, 그 환경의 Python으로 설치한다.

~~~bash
conda activate fds-model
which python
python -m pip install -r requirements.txt
~~~

### scikit-learn이 없다는 경고

현재 코드는 scikit-learn이 없으면 pandas get_dummies fallback을 사용한다. 재사용 가능한 One-Hot encoder를 저장하려면 scikit-learn과 joblib을 설치하고 파이프라인을 다시 실행한다.

### 디스크 부족 또는 메모리 부족

생성 CSV, 로그, checkpoint 크기를 확인한다.

~~~bash
df -h
du -sh data outputs checkpoints logs
~~~

오래된 산출물 삭제는 대상과 보존 여부를 먼저 확인한 뒤 수행한다. 공유 서버에서 다른 사용자의 파일이나 프로세스를 삭제하지 않는다.

## 14. 실행 전·후 체크리스트

### 로컬

- [ ] 프로젝트 루트와 Git 브랜치를 확인했다.
- [ ] compileall과 unittest가 통과했다.
- [ ] staged 파일에 raw CSV·generated CSV·비밀정보가 없다.
- [ ] 변경사항을 승인된 브랜치에 push했다.

### 원격 Jupyter Hub

- [ ] 저장소를 올바른 브랜치로 pull했다.
- [ ] data/raw/uae_ecom_fraud_100k.csv가 별도로 준비되어 있다.
- [ ] fds-model Conda 환경이 활성화되어 있다.
- [ ] nvidia-smi -i 1로 할당 GPU를 확인했다.
- [ ] GPU_DEVICE=1과 CUDA_VISIBLE_DEVICES=1을 사용했다.
- [ ] scripts/check_gpu.py가 CUDA Tensor 연산을 통과했다.
- [ ] 전처리 보고서가 COMPLETED인지 확인했다.
- [ ] 모델 입력에서 Leakage와 직접 식별자가 제거되었는지 확인했다.
- [ ] 장시간 학습 구현 시 checkpoint 주기를 설정했다.

## 15. 가장 짧은 원격 실행 예시

모든 준비가 끝난 뒤에는 다음 명령으로 기본 검증을 수행할 수 있다.

~~~bash
cd <project-root>
conda activate fds-model

export GPU_DEVICE=1
export CUDA_DEVICE_ORDER=PCI_BUS_ID
export CUDA_VISIBLE_DEVICES="$GPU_DEVICE"

nvidia-smi -i "$GPU_DEVICE"
python scripts/check_gpu.py
python -m src.preprocessing.run_preprocessing \
  --project-root . \
  --input-csv data/raw/uae_ecom_fraud_100k.csv \
  --verbose
bash scripts/train.sh --epochs 1 --batch-size 32
~~~

마지막 명령은 현재 실제 모델 학습이 아니라 smoke test다. 실제 FDS 모델을 추가한 뒤 동일한 실행 래퍼에 학습·평가·checkpoint 옵션을 연결한다.

