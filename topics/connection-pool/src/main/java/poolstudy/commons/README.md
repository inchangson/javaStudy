# 03. Commons Pool 2.12.1: 객체 생명주기와 대기열

```bash
./gradlew :topics:connection-pool:runDemo -Pdemo=poolstudy.commons.CommonsPoolDemo
./gradlew :topics:connection-pool:test --tests 'poolstudy.commons.*'
```

## 관찰부터

데모는 `maxTotal=maxIdle=1`, `testOnBorrow=true`, `maxWait=100ms`를 명시한다. 첫 객체를 반환하고 valid=false로 바꾸는 줄은 서버 없이 죽은 유휴 자원을 재현하는 fault injection이다. 일반 사용에서는 반환한 객체에 접근하면 안 된다.

출력 `replaced=true` 뒤 이벤트 순서는 `create 1 → activate 1 → validate 1 → passivate 1 → activate 1 → validate 1 → destroy 1 → create 2 → activate 2 → validate 2 → passivate 2 → destroy 2`다. 첫 destroy는 검증 실패, 마지막 destroy는 pool.close에서 온다. 테스트는 상태 초기화, 정확한 callback 순서, 소진 예외, 중복 반환 거부, 종료 후 늦은 반환까지 검증한다.

## 빌릴 때: 자체 Semaphore가 아닌 deque와 객체 상태

소스는 Maven Central `org.apache.commons:commons-pool2:2.12.1:sources`를 내려받아 확인했다. [GenericObjectPool](https://github.com/apache/commons-pool/blob/rel/commons-pool-2.12.1/src/main/java/org/apache/commons/pool2/impl/GenericObjectPool.java), [DefaultPooledObject](https://github.com/apache/commons-pool/blob/rel/commons-pool-2.12.1/src/main/java/org/apache/commons/pool2/impl/DefaultPooledObject.java), [LinkedBlockingDeque](https://github.com/apache/commons-pool/blob/rel/commons-pool-2.12.1/src/main/java/org/apache/commons/pool2/impl/LinkedBlockingDeque.java)를 함께 연다. 마지막 deque는 `java.util.concurrent`가 아니라 **Commons 내부 구현**이다.

1. 예제 `pool.borrowObject() → borrowObject(getMaxWaitDuration())`가 열림 상태와 설정을 읽는다.
2. `idleObjects.pollFirst()`로 유휴 객체를 찾고 없으면 `create(remainingWaitDuration)`를 호출한다. `createCount`는 생성 중인 슬롯도 예약한다. `makeObjectCountLock` 아래 상한을 검사하여 동시에 생성 중인 객체까지 `maxTotal`에 포함한다. 예약 후 실제 `factory.makeObject()`는 그 모니터 바깥에서 실행한다.
3. 우리 Factory는 `BasePooledObjectFactory.makeObject → create → wrap`을 통해 Resource를 `DefaultPooledObject`로 감싼다. 이 wrapper가 IDLE/ALLOCATED/RETURNING/INVALID 등의 상태와 시간 정보를 관리한다.
4. 만들 여지도 없으면 blockWhenExhausted=true일 때 `idleObjects.takeFirst()`(음수 maxWait) 또는 `pollFirst(remainingWaitDuration)`로 대기한다. deque는 `ReentrantLock`과 Condition을 이용한다. false면 바로 `NoSuchElementException`, 대기 시간 초과도 같은 예외 타입이다. Semaphore.acquire를 호출하는 구조가 아니다.
5. 얻은 wrapper의 `allocate()`가 성공하면 `factory.activateObject`, 설정에 따라 `validateObject`를 실행한다. 유휴 객체 검증 실패는 destroy 후 다음 후보를 찾는다. **방금 새로 만든 객체**의 활성화/검증 실패는 끝없는 재생성을 하지 않고 예외로 끝낸다.

2.12.1은 `Instant.now` 기반 남은 대기시간 계산을 사용한다. 버전별 세부 계산까지 모든 풀에 일반화하지 않는다. maxWait가 factory의 네트워크 연결 생성/검증 코드를 강제로 중단하는 타이머인 것도 아니다. factory 작업 자체의 timeout이 별도로 필요하다.

## 반환할 때: passivate는 라이브러리 자동 청소가 아니다

`finally → returnObject(resource) → getPooledObject(identity) → markReturningState → [testOnReturn 검증] → factory.passivateObject → deallocate` 순서다. 우리 Factory가 state를 빈 문자열로 만드는 이유로 다음 대여자가 깨끗한 값을 본다. Base factory 기본 passivate는 아무것도 하지 않으므로 설정만으로 임의 객체의 상태가 지워지지 않는다.

이후 닫힌 풀이거나 idle 수가 maxIdle에 도달했으면 destroy, 아니면 deque로 돌려보낸다. 기본 LIFO는 `addFirst`, FIFO 설정은 `addLast`다. fairness는 기다리는 스레드의 lock 정책이고 lifo는 자원 재사용 순서라 별개다. allObjects의 identity 조회와 wrapper 상태 검사로 외부 객체·중복 반환을 거부한다(이 예제는 abandoned 회수 설정을 사용하지 않는다).

passivate 실패 시 반환 경로는 객체를 폐기하고 예외를 `SwallowedExceptionListener`로 전달할 수 있다. 반환 API에 모든 정리 실패가 그대로 throw된다고 가정하면 안 된다. `close`는 idle을 비우고 대기자를 깨우며, 대여 중 객체는 나중에 반환될 때 destroy한다. minIdle 유지/eviction은 별도 설정·실행 경로이고 이 예제는 켜지 않는다.

## 작은 풀과 비교할 기준

작은 풀은 생성 완료된 참조와 허가 수만 관리한다. Commons는 생성 중 예약, wrapper 상태, factory callback, 대기·폐기 정책을 더한다. 그러나 JDBC rollback이나 Redis MULTI 취소를 알지는 못한다. **무엇을 reset/validate/destroy할지는 adapter의 책임**이다. HikariCP와 Lettuce를 읽을 때 풀 알고리즘만큼 adapter 코드를 봐야 하는 이유다.
