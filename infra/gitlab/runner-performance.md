# Runner와 CI 빌드 측정 — S15P21A207-392

## 현재 상태

2026-09-17 기준으로 동일 pipeline의 JAR 재사용과 worker 비교용 설정을 구현했습니다. 사용자 요청에 따라 시간 제약으로 반복 측정을 중단했습니다. **전체 pipeline 시간 개선이나 최적 worker 수는 입증하지 않았습니다.** 기준 조건 3회와 artifact 경로의 실제 MR 전체 통과 1회는 확보했지만, 개선 조건 3회 및 concurrent 2·3 / worker 조합 비교는 완료하지 못했습니다.

최종 설정은 **`concurrent=3`, worker override 미지정**입니다. 서버 설정은 원본 백업과 일치하고 권한 `0600`, Runner active를 확인했습니다. `concurrent=2`는 적용하지 않았으며 EC2 애플리케이션 코드와 배포 파일도 직접 수정하지 않았습니다. 임시 Runner 태그를 제거하고 태그 없는 Job 실행 허용을 유지했습니다. 측정용 pipeline과 부하 수집은 중단했습니다.

[Job 시간과 host 표본 원자료](runner-measurements-2026-09-17.json)에 기준 3회·42개 Job·중복 없는 host 표본 70개, 단독 MR 기능 검증과 과거 develop 표본을 구분해 기록했습니다. 원자료에 token·개인키·환경 변수 전체는 포함하지 않습니다.

최신 develop `99e3ee599505580cff4f532157c73998c2e53545`를 병합했습니다. S15P21A207-395의 고정 스모크 기준 선택과 develop 배포 전 필수 스모크 정책을 유지하면서, MR·develop 모두 전체 스모크에 필요한 5개 JAR을 전달하도록 통합했습니다. 중복된 임시 `resolve-smoke-base.sh`와 비교 전용 SHA override는 제거했습니다. 최종 일반 MR 검증과 이 MR의 실제 develop 배포 성공은 이 문서 작성 시점에 미확인입니다.

## 실제 Runner 조회 결과

| 항목 | 확인값 |
| --- | --- |
| 설치·실행 | `/usr/bin/gitlab-runner`, systemd `gitlab-runner.service`, active |
| 설정 파일 | 서비스 ExecStart의 `/etc/gitlab-runner/config.toml` |
| 버전·executor | Runner 19.3.1, Docker |
| CPU·메모리 | `nproc=4`, 메모리 15,785 MiB, 조회 시 available 12,701 MiB, swap 없음 |
| 전체 동시 실행 | `concurrent=3` |
| Runner별 제한 | `limit`, `request_concurrency` 명시 없음 |
| Docker 제한 | `cpus`, `memory`, `service_cpus`, `service_memory` 명시 없음 |
| Docker 실행 | privileged, `/cache` volume, cache 활성화 |
| 함께 실행 중인 개발 서비스 | 앱 5개·DB 3개·Redis healthy, Nginx 실행 중 |

`vmstat 1 4`의 첫 행(부팅 이후 평균)을 제외한 세 구간에서 runnable은 6·7·9, idle은 1·1·2%, I/O wait는 모두 0%였습니다. 근접 시점 `docker stats`에서 CI build 컨테이너 하나는 CPU 214.10%, DinD 하나는 133.32%였습니다. 짧은 관측으로 CPU 경합의 근거는 되지만 지속적인 포화나 최적 동시 실행 수를 입증하지는 않습니다. 환경 변수 전체, Runner token, SSH key는 수집하지 않았습니다.

## 기존 pipeline 표본

실제 [develop pipeline #201566](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/pipelines/201566)은 커밋 `32828bf1d8c61d84ba95a33f5809a226bc1093cd`의 실행입니다. 작업 시작 시 로컬·원격 develop은 `115bc3a324d19577cf7db1d736885f0fbb9398e0`이므로 두 결과를 같은 커밋의 성능 비교로 사용하지 않습니다. 해당 과거 pipeline 실행 당시의 `concurrent`도 확인되지 않았습니다.

| 지표 | 관측값 |
| --- | --- |
| 생성 → 완료(KST) | 11:41:25 → 12:22:50, 경과 41분 25초 |
| GitLab 표시 duration | 21분 9초, queued 1초 |
| [core-build #559304](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/jobs/559304) | duration 2분 4초, queued 2초 |
| 해당 Job의 cache 복원 / 저장 | 14초 / 27초 |
| 해당 Job의 script / Gradle | 70초 / 67초 |
| Gradle task 결과 | 8개 중 3개 FROM-CACHE, 5개 실행 |

GitLab 표시 duration과 생성·완료 경과 시간은 다릅니다. 재시도·대기·다른 pipeline 간섭을 분리하지 않은 이 표본으로 전체 시간 단축률을 계산하지 않습니다.

같은 develop pipeline의 완료 Job 22개를 추가 조회했습니다. 아래 단위는 초입니다. Docker 이미지 Job은 앞 단계에서 JAR을 만들었는데도 별도의 Gradle 실행으로 모든 task를 다시 수행했습니다. 각 Docker Job의 DinD가 분리되어 있어 서비스 사이의 Gradle cache 재사용도 관측되지 않았습니다. 이 결과는 중복 빌드의 근거이며, 새로운 구성의 시간 단축률은 동일 SHA 비교에서 별도로 계산합니다.

| 서비스 | JAR build 실행 | Docker Job 실행 | Docker 내부 Gradle | 내부 task 실행 / FROM-CACHE | deploy 실행 |
| --- | ---: | ---: | ---: | --- | ---: |
| Core | 124 | 200 | 133 | 8 / 0 | 48 |
| MCP | 108 | 159 | 97 | 6 / 0 | 29 |
| Merchant | 89 | 194 | 130 | 10 / 0 | 28 |
| Pay | 102 | 264 | 198 | 8 / 0 | 20 |
| PG | 75 | 191 | 125 | 6 / 0 | 30 |

이미지 Job은 [#559309](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/jobs/559309)·[#559310](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/jobs/559310)·[#559312](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/jobs/559312)·[#559311](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/jobs/559311)·[#559313](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/jobs/559313)입니다. 이 과거 실행의 host 자원·서비스 응답 시간은 수집하지 못했으므로 추정하지 않습니다.

## 실제 MR 검증 중 확인한 사항

- [pipeline #201851](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/pipelines/201851)의 CI 회귀 검사에서 실행 환경의 token 관련 설정값이 진단 테스트의 합성 비밀값 목록에 섞였습니다. 테스트 subprocess가 필요한 환경만 받도록 격리하고, 같은 Alpine 3.22 이미지에서 20개 검사를 통과했습니다.
- 수정 커밋 `ab9f5c129bd3e821fb73670d679a3fd4e29068c9`의 [pipeline #201871](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/pipelines/201871)에서 세 lint Job과 Core·MCP 테스트가 통과했습니다. Core Job은 `test`와 `bootJar`를 한 Gradle 실행으로 수행하고 SHA·pipeline ID·checksum metadata를 생성했습니다.
- [Core test #560528](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/jobs/560528)은 EC2 Runner #2063에서 실행됐습니다. Job 실행 1분 25초·대기 2초, Gradle 38초, 13개 task 중 6개 FROM-CACHE·7개 실행을 확인했습니다. worker override는 없었습니다.
- 같은 pipeline 도중 별도의 laptop Runner #2138이 등록돼 일부 Job을 처리했습니다. 해당 Runner는 worker 기본값 `2`를 주입했으며, [Merchant test #560531](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/jobs/560531)은 cache가 없는 상태에서 Gradle 배포본 다운로드의 DNS 조회 실패로 종료됐습니다. 두 Runner가 섞인 이 실행은 EC2 성능 비교 표본으로 사용하지 않습니다. 공유 Runner의 추가·중지 등 외부 상태 변화도 측정 조건에 포함해야 합니다.
- [Core build #560533](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/jobs/560533)은 laptop Runner에서 Gradle 없이 같은 pipeline의 JAR 검증에 성공했습니다. script는 1초 미만이었지만 artifact 다운로드 1분 49초·업로드 10초가 소요됐습니다. JAR 재사용 효과와 Runner 간 artifact 전송 비용을 구분합니다.
- 임시 태그로 모든 Job을 EC2 Runner #2063에 고정한 [pipeline #201917](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/pipelines/201917)은 lint·test·build 13개 Job을 통과했습니다. 실행 중 develop이 `14b70b7bfac2e0eff8da12da8915e91744cd223e`로 진행해 스모크의 기준 SHA 포함 검사에서 실패했습니다. 후보 `99d4c4a739ac4fc8f1cc19312ca1adda72dc00ee`의 완료 성능 표본에는 포함하지 않습니다. 기존 커밋을 보존하는 merge `7bfaa3dabc6a93e3c130741b93519e25680426b2`로 최신 develop을 반영했습니다.

다음은 #201917의 진단용 Job 측정값입니다. 모든 Job은 `concurrent=3`, worker 미지정인 같은 EC2에서 실행됐습니다. 단위는 초이며 대기와 실행을 분리했습니다.

| Job | Job ID | 대기 | 실행 |
| --- | --- | ---: | ---: |
| repository-lint | 560697 | 1 | 7 |
| backend-lint | 560698 | 9 | 81 |
| ci-config-test | 560699 | 18 | 40 |
| core-test | 560701 | 2 | 179 |
| mcp-test | 560702 | 2 | 134 |
| pay-test | 560703 | 56 | 151 |
| merchant-mock-test | 560704 | 137 | 93 |
| pg-mock-test | 560705 | 182 | 69 |
| core-build | 560706 | 25 | 26 |
| mcp-build | 560707 | 93 | 22 |
| pay-build | 560708 | 26 | 25 |
| merchant-mock-build | 560709 | 20 | 20 |
| pg-mock-build | 560710 | 3 | 17 |
| deploy-smoke (prepare 실패) | 560711 | 2 | 34 |

Core test의 Gradle 실행은 135초, 13개 task 중 5개 FROM-CACHE·8개 실행, cache 저장은 17초였습니다. PG build는 Gradle 실행 없이 cache 복원 7초·artifact 다운로드 1초·script 1초·cache 저장 1초·artifact 업로드 2초였습니다. cache 변경이 없는 Job은 기존 archive를 재사용해 재압축하지 않았습니다. cache 최적화로 절약할 수 있는 시간과 artifact 전달 비용은 별도로 판단해야 합니다.

[pipeline #201980](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/pipelines/201980)은 후보 `7bfaa3dabc6a93e3c130741b93519e25680426b2`, 기준 `14b70b7bfac2e0eff8da12da8915e91744cd223e`로 **14개 Job 전체가 통과**했습니다. 생성 13:24:38 → 완료 13:38:52 KST, 실제 경과 14분 14초였고 GitLab duration은 14분 6초였습니다. [스모크 #560954](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/jobs/560954)는 대기 3초·실행 7분 41초였습니다. 후보 이미지 5개의 동일 pipeline JAR 검증, 신규 설치, 서비스별 업그레이드, DB 데이터 보존, 고장 이미지의 배포 실패와 이전 이미지·환경 복구, 임시 자원 정리가 모두 통과했습니다.

이 실행 구간의 host 표본 25개에서 CPU idle 중앙값 23.77%, I/O wait 최대 10.11%, available memory 최솟값 10,253 MiB였고, Core health 75회는 실패 0회·응답 중앙값 4.15ms·최대 71.51ms였습니다. 개발 서비스 unhealthy 표본은 없었습니다. 다른 Job #560815가 겹쳤으며, 이 단독 기능 검증 결과를 세 pipeline 묶음의 반복 측정 중앙값에 섞지 않습니다.

이후 소스 이미지 기준 묶음 #202060·#202064·#202065 실행 중 develop이 `8b256ccbf5a5c7e9a4b8618dda22642bd3479170`로 변경됐습니다. 첫 실행은 조상 검사에서 실패했고 나머지는 중지했으므로 비교에서 제외합니다. 사용자 승인에 따라 이후 Draft 성능 비교에서만 `CI_SMOKE_COMPARISON_BASE_SHA=14b70b7bfac2e0eff8da12da8915e91744cd223e`를 고정합니다. 이 고정 변수는 당시 비교 커밋에서만 사용했으며, 이후 최신 develop 병합 시 정식 `smoke-base.sh`로 통합해 제거했습니다.

EC2의 13:02:51~13:08:49 KST, 12개 관측 구간에서 CPU idle 중앙값은 0.425%, I/O wait 최대 3.38%, available memory 최솟값은 10,643 MiB였습니다. 앱 5개는 모든 표본에서 healthy였습니다. 이 구간에는 다른 MR이 함께 실행됐으며, 전체 성능의 전후 비교가 아닙니다. 초기 Nginx 경로 probe는 404를 반환했으므로 앱 응답성 지표에서 제외하고 이후 Core `/actuator/health`를 직접 측정합니다.

## 빌드 경로 변경

```text
변경 서비스: test + bootJar(한 Gradle 실행)
            → app.jar + metadata → build(검증·전달)
            → MR 후보 이미지 / develop 배포 이미지(ci-runtime)

스모크 미변경 서비스: build(현재 SHA의 bootJar)
                 → app.jar + metadata → MR 후보 이미지(ci-runtime)

MR 기준 버전: 기준 SHA의 소스 Dockerfile → 기준 이미지
```

변경 서비스는 test/build 사이의 별도 Gradle 실행과 이미지 단계의 JAR 재빌드를 없앴습니다. artifact는 같은 pipeline에서만 전달하며 SHA·pipeline ID·서비스 경로·SHA-256을 검사합니다. 테스트가 실행된 서비스의 artifact가 없으면 실패합니다. 미변경 서비스만 MR 전체 스택 검증에 필요한 JAR을 생성합니다. 후보 스모크와 실제 배포는 같은 Dockerfile target 및 런타임을 사용합니다.

필수 lint/test/스모크, `development-ec2` 배포 직렬화, 실패 로그 수집, 자원 정리, 복구는 유지합니다. artifact를 전달하는 `build` Job에는 기존 cache 복원·저장 비용이 남아 있으므로 추가 최적화 후보입니다.

## Worker와 cache의 범위

- `CI_GRADLE_MAX_WORKERS`: 양의 정수일 때만 CLI `--max-workers` 전달. 기본 빈 문자열은 Gradle 기본값 유지. `org.gradle.parallel=true` 자체는 바꾸지 않습니다.
- Docker 소스 빌드: `GRADLE_MAX_WORKERS` build argument 사용. MR 기준 SHA가 이 ARG를 지원하지 않으면 로그로 명시합니다. 과거 Dockerfile에는 worker 제한이 적용됐다고 간주하지 않습니다.
- CI 후보·배포 이미지: Gradle을 실행하지 않으므로 worker 설정 대상이 아닙니다.
- Runner `concurrent`, Runner별 `limit`, job/service 컨테이너 CPU quota는 서로 다른 제한입니다. Gradle worker 수는 OS CPU quota가 아닙니다.
- Gradle cache: 기존 branch key와 `.gradle/caches`, `.gradle/wrapper` 유지. 기존 Job에서 로컬 cache 복원과 FROM-CACHE를 확인했습니다. 공유 cache 서버는 설정되지 않았습니다. 동시 Job의 cache 저장 경합, 압축·복원 비용, Runner slot 간 재사용은 별도 비교 대상입니다.
- Gradle configuration cache: 프로젝트의 `backend/.gradle`은 전달하지 않습니다. 위 표본에서도 configuration cache가 없어 task graph를 계산했습니다.
- BuildKit cache: `/root/.gradle` cache mount는 해당 DinD daemon 안에서 재사용됩니다. daemon 폐기 이후의 보존이나 다른 Job과의 공유를 보장하지 않습니다. 외부 cache export는 추가하지 않았습니다.

## 비교 결과와 중단 범위

고정 비교의 후보는 `814cabe997aa64d0381bbca92a4c0c3bee1a9e50`, 기준은 `14b70b7bfac2e0eff8da12da8915e91744cd223e`, 실행 Runner는 EC2 #2063입니다. 첫 기준 묶음은 2026-09-17 13:56:08~14:34:14 KST에 모두 통과했습니다.

| 조건 | Pipeline | 생성→완료 | 스모크 대기 / 실행 |
| --- | --- | --- | --- |
| A: concurrent 3, worker 기본값, 후보 소스 빌드 | [#202122](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/pipelines/202122) | 27분 33초 | 2 / 1,192초 |
| A | [#202123](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/pipelines/202123) | 33분 2초 | 83 / 1,120초 |
| A | [#202125](https://lab.ssafy.com/s15-fintech-finance-sub1/S15P21A207/-/pipelines/202125) | 37분 37초 | 3 / 1,002초 |

A의 pipeline 경과 중앙값은 **33분 2초**, 세 실행 전체 완료 시간은 **38분 6초**, 스모크 실행 중앙값은 **18분 40초**입니다. 세 실행 구간의 합집합에서 host 표본 70개를 집계했습니다. CPU idle 중앙값 1.87%, idle 5% 미만 43개 표본, I/O wait 최대 7.35%, available memory 최솟값 10,326 MiB, runnable 최댓값 45였습니다.

Core health는 210회 중 3회 접속 실패했고, 성공 응답의 중앙값 7.24ms·p95 26.81ms·최대 149.07ms였습니다. p95는 가까운 순위 방식으로 계산합니다. 실패 3회는 13:56:21 KST의 한 표본에 모여 있습니다. Core 컨테이너가 develop 이미지 `8b256ccbf5a5c7e9a4b8618dda22642bd3479170`으로 13:56:11 KST에 재시작한 직후였으며, 다음 표본부터 HTTP 200으로 돌아왔습니다. 이 외부 배포 전환의 실패를 원자료에서 삭제하지 않습니다. 개발 서비스의 unhealthy 표본은 0개, health starting 표본은 2개였습니다.

측정 대상 이외 Job은 #561454·#561455·#561457·#561494·#562152·#562343이 관측됐고, 70개 중 19개 표본에서 하나 이상 겹쳤습니다. #561494는 변수가 적용되기 전에 잘못 생성되어 취소한 본 작업의 보조 실행이며, 모두 다른 팀의 Job이라고 해석하지 않습니다. 이후 비교 묶음과 외부 부하가 달라질 수 있으므로 전체 시간 차이를 JAR 재사용 효과만으로 단정하지 않습니다.

같은 애플리케이션 SHA, 같은 변경 경로와 Job 목록, 비슷한 외부 부하를 고정합니다. MR과 develop 시나리오는 별도로 측정하며 전체 완료 시간은 생성부터 최종 필수 Job 완료까지의 경과 시간으로 기록합니다. cache가 찬 실행과 최초 실행을 섞지 않습니다.

반복 측정은 같은 조건의 MR pipeline 세 개를 짧은 간격으로 제출하는 방식입니다. pipeline별 생성→완료 시간과 중앙값, 세 pipeline을 모두 마치는 시간을 함께 기록합니다. 같은 묶음 안의 결과는 서로 자원을 공유하므로 독립적인 단독 실행 세 번과 구분합니다. 외부 Job 수·겹친 시간에 큰 차이가 있으면 조건 사이의 시간 차이를 특정 설정의 효과로 단정하지 않습니다.

소스 이미지 기준 조건은 `SMOKE_USE_CI_ARTIFACTS=false`, 개선 조건은 `true`로 실행합니다. 이 변수는 후보 이미지의 소스 빌드와 검증된 JAR 재사용 경로를 선택하며 필수 test·스모크·복구 검사는 유지합니다. 두 조건 모두 이 MR의 test+bootJar 통합 경로를 사용하므로, 과거 CI 전체 구성과의 전후 비교로 해석하지 않습니다. `concurrent=3`·worker 미지정의 소스 이미지 기준에서 시작해, 같은 동시 실행 수·worker 설정의 artifact 조건을 비교합니다. 이어서 artifact 경로를 유지한 채 worker만 `2`로, 마지막으로 `concurrent`만 `2`로 바꿉니다. 각 단계에서는 한 조건만 변경합니다.

수동 실행 변수 여러 개를 입력할 때는 [GitLab의 URL query 사전 입력 방식](https://docs.gitlab.com/ci/pipelines/#run-a-pipeline-by-using-a-url-query-string)을 사용할 수 있습니다. MR 실행 URL의 `merge_request_iid=119`를 유지하고 `var[CI_TARGET_RUNNER_TAG]`, `var[CI_GRADLE_MAX_WORKERS]`, `var[SMOKE_USE_CI_ARTIFACTS]` 값을 입력한 뒤 화면에서 확인합니다. token이나 개인키를 URL 변수에 넣지 않습니다.

아래 절차 중 1번만 완료했습니다. artifact 개선 묶음 #202384·#202386·#202387은 시간 제약으로 취소했으며, 완료 성능 표본에서 제외합니다. 2~5번의 조건 간 비교는 미완료입니다.

1. `concurrent=3`, worker 미지정, 후보 소스 이미지 기준을 3회 이상 기록합니다.
2. 같은 후보 SHA에서 artifact 이미지의 `concurrent=3`·worker 미지정, `concurrent=3`·worker `2`, `concurrent=2`·worker `2` 조건을 각각 3회 이상 비교합니다. Runner 설정 변경은 승인된 범위에서만 수행하며 함께 실행된 다른 팀 Job을 기록합니다.
3. 각 실행의 pipeline ID·SHA·cache 상태, 각 Job의 queued/duration, 전체 경과, CPU idle/runnable/I/O wait·메모리, 개발 서비스 health·동일 읽기 요청의 응답 시간과 실패율을 기록합니다.
4. cache 복원·저장 시간, Gradle FROM-CACHE/UP-TO-DATE 비율, Docker CACHED 단계를 구분합니다. 공유 cache를 삭제하는 방식으로 최초 실행을 만들지 않습니다.
5. 3회 중앙값과 실패 여부를 비교합니다. 개발 서비스 회귀나 전체 시간 악화가 있으면 그 설정을 채택하지 않습니다.

| 항목 | 상태 |
| --- | --- |
| 실제 서버 설치·설정·단기 부하 조회 | 완료 |
| 과거 Job 시간과 cache 적중 표본 | 확인, 통제된 기준 측정 아님 |
| 동일 조건 3회 기준·개선 측정 및 중앙값 | 기준 3회 완료, 개선 조건은 사용자 요청으로 중단 |
| Runner 2·3 / worker 미지정·2 비교 | 미수행, concurrent 3 원본 유지 |
| 최종 worker·동시 실행 수 선정 | 개선이 입증되지 않아 기존 concurrent 3·worker 미지정 유지 |
| 실제 MR 전체 스모크·develop 배포 | 이전 후보 artifact MR #201980 통과, 최종 후보 MR·본 변경의 develop 배포는 미확인 |

## 적용과 원복

저장소 변경은 작업 브랜치의 커밋과 MR을 통해 반영합니다. 서버 checkout에서 코드를 편집하지 않습니다. worker 비교는 GitLab의 MR pipeline 실행 화면에서 해당 pipeline에만 `CI_GRADLE_MAX_WORKERS` 값을 넣어 실행합니다. 변수를 제거하거나 빈 값으로 돌리면 기존 worker 기본값으로 복귀합니다.

Runner 동시 실행 비교는 수행하지 않고 종료했습니다. 재개 시 절차는 다음과 같습니다.

1. 다른 팀의 Job과 개발 서비스 상태를 확인하고 측정 시간을 확보합니다.
2. `/etc/gitlab-runner/config.toml`을 서버 내부에 권한을 보존해 백업합니다. 백업에는 token이 있으므로 저장소나 artifact에 복사하지 않습니다. 기존 백업을 덮어쓰지 않습니다.
3. `concurrent` 한 항목만 `3`에서 `2`로 바꾸고 TOML 파싱 및 적용값을 확인합니다. 코드·배포 디렉터리·환경 파일은 변경하지 않습니다.
4. 측정 후 원본 백업을 복원하고 `concurrent=3`, Runner active, 서비스 health를 확인합니다. 설정 자동 재로딩을 사용하며 진행 중인 Job을 강제로 재시작하지 않습니다.

CI 경로 전체를 되돌려야 하면 MR의 변경 커밋을 역순으로 revert한 별도 작업 브랜치에서 검증하고 MR로 반영합니다. 운영 서버에서 Dockerfile이나 스크립트 일부만 되돌리지 않습니다.

## 로컬 검증

- 최종 develop 병합 후 `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -v`: 34개 통과. pipeline 고정 기준·MR/develop 전체 artifact 의존 관계·필수 스모크 gate 포함
- 최종 CI/배포 스크립트 7개의 ShellCheck 0.10.0과 `git diff --check`: 통과. POSIX shell/Bash 문법 검사도 CI에 유지
- `./gradlew checkstyleMain checkstyleTest build --max-workers=2 --no-daemon`: 통과(1분 43초). 개발자 PC 결과로 EC2 성능 비교에 사용하지 않음
- Core DB 통합 테스트: 임시 로컬 PostgreSQL 17에서 320개, 실패·오류·skip 0. 나머지 Backend 결과와 합쳐 1,031개 테스트 확인
- Podman: 5개 CI target에 합성 JAR을 넣어 추출 바이트와 사용자 `app`, `/app`, 포트 8080, Java entrypoint 일치 확인. 앱 전체 스모크와는 별개
- Docker 소스 builder: 대체 Gradle 실행기로 `--max-workers=2` 전달 확인. 실제 Core Dockerfile도 로컬 Podman에서 `GRADLE_MAX_WORKERS=2`, target `builder`로 빌드해 Gradle 8개 task 실행·성공(15초) 확인 후 임시 이미지를 정리함. EC2 성능 비교 수치에 포함하지 않음

설정 의미는 [GitLab Runner advanced configuration](https://docs.gitlab.com/runner/configuration/advanced-configuration/), [GitLab cache와 artifact](https://docs.gitlab.com/ci/caching/), [Docker multi-stage build](https://docs.docker.com/build/building/multi-stage/), [Gradle CLI](https://docs.gradle.org/current/userguide/command_line_interface.html)를 기준으로 확인했습니다.
