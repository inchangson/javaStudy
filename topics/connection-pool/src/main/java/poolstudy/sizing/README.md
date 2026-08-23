# 06. 연결을 더 늘리면 왜 오히려 느려질까?

연결 풀의 크기는 일을 처리하는 서버의 능력이 아니라 **동시에 자원을 점유하게 허용하는 상한**이다. 상한을 늘리면 풀 앞에서 기다리던 요청이 DB 안의 락·CPU·I/O 경합에 참여할 수 있다. 유용한 병렬성이 늘지 않으면 처리량은 정체하고, 경합 자체의 비용이 증가하면 오히려 감소한다. 다음 두 실험은 이 차이를 분리한다.

## 실행과 측정 경계

```bash
./topics/connection-pool/scripts/measure.sh
# 기본 출력: topics/connection-pool/build/results/sizing.csv
./gradlew :topics:connection-pool:test --tests 'poolstudy.sizing.*'
```

`SizingDemo.main → run → batch → pool.borrow → Handle.work → Handle.close`를 따라 읽는다. pool 크기 1/2/4/8/16, 가상 스레드 client 24개, client마다 12회로 측정당 288회다. 각 client는 직전 요청이 완료되어야 다음 요청을 보낸다. 총 3라운드이며 seed 8112026으로 크기 순서를 섞는다. 각 시나리오에서 client당 2회 warmup을 빼고 측정한다. Hikari는 모든 슬롯을 실제로 빌려 채우고 반환한 뒤 warmup한다. 비동기 minIdle 보충과 연결 생성 시간이 본 측정에 섞이는 것을 줄이기 위해서다.

CountDownLatch로 worker가 모두 준비된 뒤 측정 시계를 시작한다. 획득 timeout은 10초다. 성공·timeout을 별도로 세고, 예상하지 못한 SQL/작업 오류는 실험 자체를 실패시킨다. DB 최종 counter 증가량과 성공 횟수가 다르면 결과를 출력하지 않고 실패한다.

| CSV 필드 | 실제 측정하는 범위 |
|---|---|
| acquire_p95_ms | 요청 시작 → borrow 반환, 성공 요청의 p95 |
| hold_mean_ms | borrow 성공 → close 완료, 성공 요청 평균 |
| backend_mean_ms | 모형: sleep 서비스 시간 / H2: executeUpdate 호출 시간 |
| total_p50_ms / total_p95_ms | 각 요청 시작 → 자원 반환 완료, timeout 요청은 실패 종료까지 포함 |
| throughput_ops_s | 성공 횟수 / 전체 batch 경과 시간 |
| timeouts | 획득에서 timeout된 요청 수. 연결을 못 빌렸으므로 hold 통계에 포함하지 않음 |

H2의 backend 시간은 SQL 처리와 락 대기를 함께 포함한다. 순수 lock timer로 이름 붙이지 않았다. prepareStatement, commit, close는 hold에 포함되지만 backend에는 포함되지 않는다. 각 성공 요청에서 `total = acquire + hold`가 성립하는지도 테스트한다. 평균과 p95는 다른 집계이므로 표의 p95 acquire에 평균 hold를 더해 p95 total을 계산하면 안 된다.

## 실험 A: 가정을 드러낸 경합 모형

`modelPool → TinyPool.borrow → modeledServiceNanos`는 외부 서버 없이 동시 활성 수 n을 센다. 기본 서비스 시간은 2ms, 유용한 동시성은 4라고 **정의**한다. n이 4를 넘으면 `0.5ms × (n-4)^2`를 더해 sleep한다. n은 작업 진입 시점의 값으로 고정한다. 완료 시 active를 감소시키고 lease.close로 반환한다.

따라서 '실제 DB의 서비스 비용이 제곱으로 증가한다'는 발견이 아니다. **경합으로 작업 시간이 늘어나는 시스템이라면 풀 크기를 늘려 손해를 볼 수 있다**는 조건부 예제를 실행 가능하게 만든 것이다. 기준 용량·비용 함수를 바꿔 그래프 모양이 달라지는지도 실험할 수 있다. 실제 CPU나 디스크 경합을 측정한 값으로 부르면 안 된다.

## 실험 B: 실제 HikariCP + H2의 한 행 경합

`h2Pool.borrow → HikariDataSource.getConnection`의 대여 과정은 [Hikari 패키지](../hikari/README.md)를 따른다. 각 작업은 autoCommit=false로 같은 행에 `UPDATE counter SET value_count=value_count+1 WHERE id=1`을 실행하고, **락을 가진 채 2ms sleep한 뒤 commit**한다. DB 내부 대기를 재현하도록 의도적으로 긴 트랜잭션을 만든 것이다.

고정한 H2 **2.3.232** sources에서 다음 경로를 확인했다.

1. Hikari의 statement proxy → `JdbcPreparedStatement.executeUpdate → executeUpdateInternal → command.executeUpdate`로 들어간다.
2. `Command.executeUpdate → CommandContainer.update → prepared.update`가 SQL 실행 계획을 호출한다. UPDATE에서는 `Update.update → table.lockRow(session, oldRow, -1)`로 행 잠금을 시도한다.
3. `MVTable.lockRow → MVPrimaryIndex.lockRow → TransactionMap.lock → set`이 해당 키의 transaction 소유권을 확인한다. 같은 행을 변경 중인 다른 transaction이 있으면 `transaction.waitFor(blockingTransaction, ...)`로 간다.
4. `Transaction.waitFor → waitForThisToEnd`는 대상 transaction 모니터에서 남은 시간만 wait하고, 종료/rollback/timeout/교착 상태를 검사한다. 연결을 더 만들었다고 같은 행의 쓰기가 모두 동시에 진행되는 것은 아니다.
5. 호출자의 `connection.commit → H2 JdbcConnection.commit → COMMIT command → SessionLocal.commit → transaction.commit`가 트랜잭션을 완료한다. 이 뒤 경쟁 요청이 다시 진행할 수 있다. 우리 sleep은 UPDATE가 성공한 뒤, commit 이전이라 같은 행에 대한 배타적 점유 시간을 늘린다.

원본: [JdbcPreparedStatement](https://github.com/h2database/h2database/blob/version-2.3.232/h2/src/main/org/h2/jdbc/JdbcPreparedStatement.java), [CommandContainer](https://github.com/h2database/h2database/blob/version-2.3.232/h2/src/main/org/h2/command/CommandContainer.java), [Update](https://github.com/h2database/h2database/blob/version-2.3.232/h2/src/main/org/h2/command/dml/Update.java), [MVPrimaryIndex](https://github.com/h2database/h2database/blob/version-2.3.232/h2/src/main/org/h2/mvstore/db/MVPrimaryIndex.java), [TransactionMap](https://github.com/h2database/h2database/blob/version-2.3.232/h2/src/main/org/h2/mvstore/tx/TransactionMap.java), [Transaction](https://github.com/h2database/h2database/blob/version-2.3.232/h2/src/main/org/h2/mvstore/tx/Transaction.java). `downloadSources`로 받은 jar에서도 같은 클래스명을 찾을 수 있다.

## 실제 측정 결과

[원본 CSV](../../../../../results/sizing-2026-09-12.csv)는 **실제 측정일 2026-09-12**, Apple M5 / Darwin arm64 / Corretto 21.0.11에서 얻었다. 학습용 커밋 일자와 측정일은 구분한다. 아래는 각 크기의 **3회 결과별 지표의 중앙값**이다. 모든 30개 측정은 288회 성공, timeout 0이고 DB/모형 counter와도 일치했다. 여러 실행의 p95를 합쳐 다시 계산한 모집단 p95가 아니다.

| 모형 크기 | 처리량 ops/s | 획득 p95 ms | 점유 평균 ms | 전체 p95 ms |
|---|---:|---:|---:|---:|
| 1 | 398.55 | 58.48 | 2.50 | 61.00 |
| 2 | 794.66 | 28.18 | 2.51 | 30.73 |
| 4 | 1589.38 | 13.41 | 2.51 | 15.98 |
| 8 | 834.17 | 21.58 | 9.44 | 31.98 |
| 16 | 224.99 | 50.30 | 69.61 | 124.44 |

| H2 크기 | 처리량 ops/s | 획득 p95 ms | 점유 평균 ms | SQL 평균 ms | 전체 p95 ms |
|---|---:|---:|---:|---:|---:|
| 1 | 391.70 | 273.72 | 2.55 | 0.04 | 276.34 |
| 2 | 390.47 | 252.10 | 5.09 | 2.55 | 256.91 |
| 4 | 392.12 | 237.85 | 9.89 | 7.36 | 245.12 |
| 8 | 387.35 | 184.91 | 18.93 | 16.40 | 221.94 |
| 16 | 393.49 | 0.02 | 33.69 | 31.16 | 271.90 |

모형은 설정한 경합 비용 때문에 크기 4 이후 처리량이 떨어진다. H2는 처리량이 약 390ops/s로 정체하며 크기별 일관된 처리량 하락은 관측하지 못했다. 대신 큰 풀에서 획득 대기가 줄어도 SQL/점유 시간이 늘었다. 이것이 **풀의 대기 지표만 보고 문제가 사라졌다고 판단할 수 없는** 근거다. 두 실험의 처리량으로 TinyPool과 Hikari 자체의 성능 순위를 매기면 안 된다. 수행 작업이 다르다.

H2 크기 16에서 획득 p95가 작다고 모든 요청이 즉시 획득했다는 뜻은 아니다. Hikari의 같은 스레드 재획득과 락 경합, client별 종료 시점 때문에 대기 분포가 치우칠 수 있다. 전체 p95가 단조롭게 변할 이유도 없다. 이 표에서 공정한 요청 처리나 최악 지연까지 증명하지 않는다.

## 풀별 코드 구조 비교

| 구현 | 대여/대기 메커니즘 | 용량·소유 상태 | 반환·정리 | 소진 시 |
|---|---|---|---|---|
| Semaphore | AQS shared acquire | permit 수, 객체 소유권 없음 | release가 수만 증가 | block / timed false / interrupt |
| TinyPool | Semaphore + 모니터로 보호한 ArrayDeque | 고정 참조 + Lease CAS | 한 번만 FIFO 반환, 물리 close/reset 없음 | TimeoutException |
| Commons Pool | 자체 LinkedBlockingDeque + lock/condition | 생성 예약 + PooledObject 상태 + identity 조회 | validate/passivate/destroy callback | 설정에 따라 대기 또는 NoSuchElementException |
| HikariCP | ThreadLocal 후보 → shared CAS → SynchronousQueue handoff | PoolEntry 상태 + 비동기 생성 상한 | JDBC proxy rollback/reset → recycle | SQLTransientConnectionException |
| Lettuce 동기 | ConnectionPoolSupport → Commons Pool | Commons 상태 + 반환용 wrapper | wrapper.close는 return, factory.destroy는 원본 close; 자동 DISCARD 없음 | Commons 설정을 따름 |
| Lettuce 비동기 | BoundedAsyncPool의 ConcurrentLinkedQueue + future | 객체 수 + 생성 예약, Commons wrapper 상태 없음 | release / async destroy, 자동 세션 reset 없음 | 기다리는 borrower queue 없이 실패 future |

공통 원리는 유휴 객체 재사용, 동시 점유 제한, 획득 실패 처리, 반환 책임이다. **모두 Semaphore로 구현되었다**거나 **close가 모두 같은 의미**라는 설명은 틀리다. Lettuce의 공유 연결은 여러 in-flight 명령을 받아 JDBC의 한 연결 대여와 다른 병렬성 경계를 갖는다. 반환하지 않는 누수는 모든 모델에서 가용성을 줄이지만 발견·회수·종료 방식까지 같지는 않다.

```mermaid
flowchart LR
    R[요청] --> A[애플리케이션 대기]
    A --> P[풀 획득 대기]
    P --> C[연결 점유]
    C --> D[서버 CPU · I/O · 락 대기]
    D --> E[실행 완료 · 상태 정리]
    E --> F[풀 반환]
```

## 결과를 읽고 다음 실험을 설계하기

- 요청 전체 시간은 풀 획득뿐 아니라 연결 점유와 다른 애플리케이션 대기도 포함한다. getConnection이 빨라도 쿼리/commit이 느리면 전체는 느리다. 안정된 구간에서 Little의 법칙 `평균 동시 점유 ≈ 완료율 × 평균 점유 시간`으로 **같은 경계**의 수치를 비교할 수 있다. 이것만으로 최적 풀 크기나 p99를 계산할 수는 없다.
- 외부 API를 호출하거나 파일을 읽는 동안 트랜잭션·연결을 쥐고 있으면 유용한 DB 작업 없이 점유 시간이 늘어난다. 이 실험의 sleep을 commit 뒤로 옮겨서 비교해 보자. 단, 보호해야 하는 업무 트랜잭션 경계를 임의로 깨면 안 된다.
- 현재 부하는 closed-loop다. 느려지면 새 요청도 늦게 발생하므로 일정 도착률 폭주·무한 대기열·coordinated omission을 평가하지 못한다. 운영 용량을 정하려면 별도의 도착률 부하, 전체 지연 histogram, timeout 비율, 서버 CPU/락/I/O 지표를 함께 수집해야 한다.
- 동시 부하 24, 요청 288, 짧은 warmup과 세 번 반복은 학습용이다. 신뢰구간, 장시간 GC, 네트워크 왕복, 실제 데이터 분포, 가상 스레드와 플랫폼 스레드 차이까지 검증한 결과가 아니다. 테스트는 속도 순위를 단언하지 않고 업데이트 정합성과 측정 경계만 확인한다.
- 여러 인스턴스가 같은 DB를 쓰면 총 잠재 연결 수는 대략 각 인스턴스 maximumPoolSize의 합이다. 인스턴스 수와 풀 크기를 동시에 늘릴 때 서버 측 부하를 따로 확인한다. Hikari의 [공식 sizing 설명](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)은 작은 풀의 효과와 자원 점유 문제를 설명한다. 해당 문서의 경험식을 환경과 무관한 정답으로 가져오지 않는다.

읽은 코드와 실험이 답하는 범위는 명확하다. 풀을 키우면 입장 가능한 요청이 늘지만 서버의 유용한 병렬성까지 늘지는 않는다. 그 차이를 확인하려면 대여 비용, 점유 시간, 서버 실행/대기 시간을 분리해야 한다.
