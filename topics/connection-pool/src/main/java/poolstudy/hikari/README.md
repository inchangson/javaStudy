# 04. HikariCP 6.3.0: Connection.close는 무엇을 닫나?

```bash
./gradlew :topics:connection-pool:runDemo -Pdemo=poolstudy.hikari.HikariDemo
./gradlew :topics:connection-pool:test --tests 'poolstudy.hikari.*'
```

H2 **2.3.232** 인메모리 DB를 사용하므로 DB 서버는 필요 없다. Hikari 크기는 1, minimumIdle도 1로 정해 같은 물리 연결 재사용을 관찰한다. 기대 결과는 `Observation[physicalReused=true, proxyReplaced=true, autoCommitReset=true, committedRows=0]`이다. 이 실험은 JDBC 생명주기 검증이며 원격 DB 성능 벤치마크가 아니다.

## 대여: 애플리케이션에서 ConcurrentBag까지

읽은 원본은 Maven `com.zaxxer:HikariCP:6.3.0:sources`다. 고정 태그 [HikariDataSource](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-6.3.0/src/main/java/com/zaxxer/hikari/HikariDataSource.java), [HikariPool](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-6.3.0/src/main/java/com/zaxxer/hikari/pool/HikariPool.java), [ConcurrentBag](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-6.3.0/src/main/java/com/zaxxer/hikari/util/ConcurrentBag.java)를 연다.

1. `HikariDemo.newPool → new HikariDataSource(config)`는 설정을 검증·복사하고 바로 `HikariPool`을 시작한다. 인자 없는 생성자의 첫 getConnection 지연 초기화 경로와 구분한다.
2. `observe → dataSource.getConnection → fastPathPool.getConnection → HikariPool.getConnection(connectionTimeout)`로 들어간다. suspendResumeLock은 풀 일시중단 기능과 관련 있고 풀 용량 semaphore가 아니다.
3. `connectionBag.borrow(timeout, MILLISECONDS)`는 ThreadLocal 리스트에서 먼저 후보를 꺼내 `STATE_NOT_IN_USE → STATE_IN_USE` CAS를 시도한다. 실패하면 sharedList를 스캔한다. **ThreadLocal은 소유권 보장이 아니라 빠른 후보 탐색**이다. 같은 entry가 다른 스레드에서 이미 대여되었을 수 있으므로 CAS가 필요하다.
4. 그래도 없으면 waiters를 증가시킨 상태로 listener.addBagItem을 요청하고 `handoffQueue.poll`로 남은 시간을 기다린다. handoffQueue는 fair `SynchronousQueue`다. 객체 저장소는 sharedList이고 handoff는 반환자·대기자의 전달 통로다. 전체 대여 API가 FIFO라는 뜻은 아니다. 빠른 ThreadLocal/shared 경로는 별도로 존재한다.
5. listener는 `HikariPool.addBagItem → addConnectionExecutor → PoolEntryCreator`로 이어진다. `shouldContinueCreating`이 maximumPoolSize, minimumIdle, 대기자 수와 idle 수를 보고 생성 여부를 정한다. maximumPoolSize를 늘리는 것은 상한을 올리는 것이지 모든 요청에 연결을 즉시 제공하는 명령이 아니다.
   새 물리 연결은 `createPoolEntry → PoolBase.newPoolEntry → newConnection → dataSource.getConnection`으로 만든다. 이 예제의 jdbcUrl 설정은 DriverDataSource를 사용하여 `driver.connect → H2 Driver.connect`로 이어진다. 초기 JDBC 상태 설정과 유효성 확인이 성공해야 PoolEntry가 bag에 들어온다.
6. bag에서 받은 entry가 eviction 대상이거나 검증에 실패하면 `closeConnection`으로 제거·종료하고 남은 시간으로 재시도한다. 최근 사용한 연결은 aliveBypassWindow 조건에 따라 검증을 생략할 수 있다. 성공하면 `PoolEntry.createProxyConnection → ProxyFactory.getProxyConnection`으로 새 JDBC proxy를 반환한다.
7. 획득 실패는 `createTimeoutException → SQLTransientConnectionException`이다. 인터럽트는 플래그를 복구하고 SQLException으로 감싼다. connectionTimeout은 쿼리 실행 시간 제한이 아니다. 최초 초기화, driver 연결/검증, pool suspension도 각 경계를 따로 봐야 한다.

## 반환: proxy 추적 상태가 만드는 rollback/reset

[ProxyConnection](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-6.3.0/src/main/java/com/zaxxer/hikari/pool/ProxyConnection.java), [ProxyPreparedStatement](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-6.3.0/src/main/java/com/zaxxer/hikari/pool/ProxyPreparedStatement.java), [PoolBase](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-6.3.0/src/main/java/com/zaxxer/hikari/pool/PoolBase.java), [PoolEntry](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-6.3.0/src/main/java/com/zaxxer/hikari/pool/PoolEntry.java)를 이어 읽는다.

`first.setAutoCommit(false)`는 delegate 설정과 함께 proxy의 isAutoCommit, dirtyBits를 바꾼다. `prepareStatement → executeUpdate`는 ProxyPreparedStatement에서 `connection.markCommitStateDirty()`를 호출한다. 따라서 close 시 미완료 트랜잭션이라는 사실을 proxy가 안다.

try-with-resources가 `first.close → ProxyConnection.close`를 부르면 다음 순서다.

1. 추적하던 statement들을 닫고 leak 감시 작업을 취소한다.
2. dirty transaction && !autoCommit이면 물리 delegate.rollback을 호출한다. 데모의 INSERT가 다음 대여자의 COUNT에서 0이 되는 이유다.
3. dirtyBits가 있으면 `PoolEntry.resetConnectionState → PoolBase.resetConnectionState`로 설정을 복구한다. autoCommit, readOnly, isolation, catalog, networkTimeout, schema 중 추적된 변경을 기준 설정과 비교한다.
4. 경고를 지우고 finally에서 proxy의 delegate를 CLOSED_CONNECTION으로 바꾼 뒤 `PoolEntry.recycle → HikariPool.recycle`로 간다. 정상 entry는 `ConcurrentBag.requite`가 NOT_IN_USE로 바꾸고 대기자에게 handoff를 시도하거나 현재 스레드의 후보 목록에 저장한다. eviction entry는 물리 종료 경로로 간다.

즉 **대여마다 proxy는 새로 만들지만 물리 JDBC 연결은 재사용**한다. 테스트는 unwrap으로 참조만 비교하고 직접 delegate를 조작하지 않는다. unwrap한 연결에서 setAutoCommit하거나 SQL로 세션 상태를 바꾸면 proxy 추적을 우회할 수 있다. 임의 세션 변수·임시 테이블·driver 상태까지 전부 청소하는 풀이라고 해석하면 안 된다.

## 종료·누수·대기 실험

테스트는 미커밋 rollback, autoCommit 복구, 중복 proxy.close, 닫힌 proxy 사용 실패, 물리 연결 재사용과 DataSource 종료 후 물리 close를 확인한다. 또 연결 하나를 계속 빌린 상태에서 다음 getConnection이 timeout되는지, MXBean의 실제 대기자 수가 1이 된 뒤 반환했을 때 기다리던 호출이 성공하는지 검사한다. 짧은 sleep 후 '아마 기다리겠지'로 판단하지 않는다.

`HikariDataSource.close → HikariPool.shutdown`은 idle뿐 아니라 active 연결도 abort/close하는 경로를 갖는다. Commons의 '대여 중 객체는 반환 시 폐기'와 종료 보장이 같지 않다. 사용 중인 요청을 마친 뒤 풀을 닫는 애플리케이션 종료 순서가 필요하다. leakDetectionThreshold는 오래 대여된 연결을 알리는 진단이며 자동 회수를 의미하지 않는다.

다음 질문은 'bag의 CAS를 더 빠르게 하면 모든 병목이 사라질까?'다. 서버 실행·락 대기가 지배하면 대여 관리 비용을 줄여도 연결을 늘릴수록 처리량이 감소할 수 있다. 마지막 실험에서 획득 대기와 실제 자원 점유를 따로 측정한다.
