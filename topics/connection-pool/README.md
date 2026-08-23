# Semaphore에서 연결 풀까지

`docs/topic-ideas.md` 1번: **연결을 더 늘리면 왜 오히려 느려질까?**

Java 21로 실행하며 외부 라이브러리는 각 단계에서 버전을 고정한다. 각 패키지의 README는 예제 호출부에서 라이브러리 내부 획득·반환·실패 경로까지 이어진다. 코드와 문서를 함께 읽고 해당 커밋의 테스트를 실행한다.

## 학습 순서 / 커밋 시간

커밋 날짜는 실제 학습 이력의 증명이 아니라 요청에 맞춰 재구성한 학습 일정이다. 2026년 8월 11일 이후, 고정 seed `8112026`의 1~3일 간격으로 생성했다. 시간대는 Asia/Seoul이며 평일은 20시 이후다.

| 단계 | 일정 (+09:00) | 내용 |
|---|---|---|
| 01 | 08-12 21:48:23 | [Semaphore](src/main/java/poolstudy/semaphore/README.md): 허가 수와 소유권 |
| 02 | 08-14 21:59:41 | [작은 객체 풀](src/main/java/poolstudy/tiny/README.md): lease, 시간 제한, 종료 |
| 03 | 08-16 21:07:49 | [Commons Pool](src/main/java/poolstudy/commons/README.md): 생성·검증·반환·폐기 |
| 04 | 08-17 21:18:23 | [HikariCP](src/main/java/poolstudy/hikari/README.md): ConcurrentBag과 JDBC proxy |
| 05 | 08-20 21:52:38 | [Lettuce](src/main/java/poolstudy/lettuce/README.md): Commons 기반 동기 풀과 비동기 풀 |
| 06 | 08-23 21:03:40 | [크기 실험](src/main/java/poolstudy/sizing/README.md): 획득 대기와 자원 경합 분리 |

```bash
./gradlew :topics:connection-pool:test
./gradlew :topics:connection-pool:runDemo -Pdemo=poolstudy.semaphore.SemaphoreDemo
```

## 실행 메뉴

모든 명령은 저장소 루트에서 실행한다. 각 커밋까지 추가된 단계만 실행할 수 있다.

```bash
./gradlew :topics:connection-pool:runDemo -Pdemo=poolstudy.tiny.TinyPoolDemo
./gradlew :topics:connection-pool:runDemo -Pdemo=poolstudy.commons.CommonsPoolDemo
./gradlew :topics:connection-pool:runDemo -Pdemo=poolstudy.hikari.HikariDemo
./topics/connection-pool/scripts/verify-redis.sh
./topics/connection-pool/scripts/measure.sh
```

`test`는 Redis 없이 수행하는 단위/로컬 H2 테스트다. `redisTest`는 실제 Redis가 필수이며 일반 test에서 제외했다. verify-redis.sh는 Docker가 실행 중이어야 하며 임시 Redis 7.4.2 컨테이너를 만들어 통합 테스트와 Lettuce 데모를 실행하고 정리한다. Docker 대신 직접 준비한 서버에는 `REDIS_URI=redis://127.0.0.1:6379/0 ./gradlew :topics:connection-pool:redisTest`를 사용한다.

## 원본 소스 다시 읽기

| 대상 | 고정 버전 | 확인 방법 |
|---|---|---|
| JDK | Java 21, 관찰 런타임 Corretto 21.0.11 | JDK의 lib/src.zip, Semaphore/AQS |
| Commons Pool | 2.12.1 | GenericObjectPool / DefaultPooledObject / 내부 LinkedBlockingDeque |
| HikariCP | 6.3.0 | HikariPool / ConcurrentBag / ProxyConnection / PoolBase |
| Lettuce | 6.5.5.RELEASE | ConnectionPoolSupport / ConnectionWrapping / BoundedAsyncPool |
| H2 | 2.3.232 | Update / MVPrimaryIndex / TransactionMap / Transaction |

```bash
./gradlew :topics:connection-pool:downloadSources
# topics/connection-pool/build/library-sources/*-sources.jar
# IDE에서 sources.jar를 연결하거나 jar tf / unzip -p로 해당 클래스를 읽는다.
./gradlew :topics:connection-pool:dependencyInsight --dependency commons-pool2 --configuration runtimeClasspath
```

패키지 README의 링크는 고정 tag를 가리키고 설명은 위 sources jar와 대조했다. API 계약과 특정 버전에서 관찰한 구현을 구분한다. 특히 취소 경합·fairness·종료 정책은 다른 버전에서도 동일하다고 단정하지 않는다.

## 검증한 결과와 한계

- Semaphore의 실패 시 permit 보존, 작은 풀의 동일 참조 동시 대여 금지 및 중복 반환 방지.
- Commons의 callback 순서, 검증 실패 교체, timeout, 종료 후 반환 시 폐기.
- Hikari의 실제 H2 rollback/reset, proxy와 물리 연결 생명주기, 소진/대기자 전달.
- Lettuce 실제 Redis의 MULTI 상태 누출, 동기·비동기 PING; 서버 없는 테스트로 async 생성 예약·실패·취소·반환 오류 검증.
- [크기 실험 원본 CSV](results/sizing-2026-09-12.csv)의 30개 측정과 각 측정의 counter 정합성. 처리량을 단언하는 flaky 테스트는 두지 않았다.

결과는 라이브러리 성능 순위가 아니다. H2 한 행 경합은 처리량 정체와 점유 시간 증가를, 명시적 비용 모형은 과도한 동시성에서 처리량 하락을 보여준다. 실제 서버에 맞는 수치는 별도 부하 측정이 필요하다.

## 커밋 단위로 따라가기

```bash
git log --reverse --format='%h %ad %s' --date=iso -- topics/connection-pool
# 관심 단계의 코드/문서 변경 보기
git show <커밋해시>
# 현재 작업 디렉터리를 바꾸지 않고 해당 시점 전체를 실행할 별도 checkout 만들기
git worktree add --detach ../java-study-pool-lesson <커밋해시>
```

별도 checkout에서 `./gradlew :topics:connection-pool:test`를 실행한다. 각 소주제 커밋에는 그 단계의 구현·패키지 문서·검증 테스트가 함께 들어 있다. 6단계의 source 다운로드와 전체 비교표는 마지막 커밋에서 사용할 수 있다.
