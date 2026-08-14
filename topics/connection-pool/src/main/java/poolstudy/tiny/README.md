# 02. 작은 객체 풀: permit + 객체 큐 + lease

```bash
./gradlew :topics:connection-pool:runDemo -Pdemo=poolstudy.tiny.TinyPoolDemo
./gradlew :topics:connection-pool:test --tests 'poolstudy.tiny.*'
```

## 호출 경로와 불변식

`TinyPoolDemo.main → TinyPool.borrow → Semaphore.tryAcquire(timeout) → synchronized(pool) → idle.removeFirst → new Lease`가 대여 경로다. `Lease.close → AtomicBoolean.compareAndSet(false, true) → owner.giveBack → idle.addLast → permits.release`가 반환 경로다.

- 생성 시 객체를 모두 받아 고정 크기로 시작한다. `IdentityHashMap`을 사용하는 Set으로 같은 참조가 두 슬롯에 들어가는 것을 거부한다. `equals`가 같은 서로 다른 객체는 허용한다. 풀의 배타성 단위는 참조이기 때문이다.
- 열려 있는 동안에는 **idle 객체 수 + 대여 객체 수 = 용량**이다. 아직 큐에서 제거하지 않은 획득자, 아직 permit을 반환하지 않은 반환자가 있을 수 있어 `availablePermits == idle.size`는 모든 순간에 성립하지 않는다. 안정된 시점에서만 같다.
- ArrayDeque 자체는 thread-safe하지 않다. `borrow/giveBack/close`의 큐 조작을 같은 모니터로 직렬화한다. permit을 기다릴 때는 모니터를 잡지 않는다. 모니터를 잡은 채 기다리면 반환자가 진입하지 못해 교착할 수 있다.
- `giveBack`은 **객체를 큐에 넣은 뒤** permit을 공개한다. 반대로 하면 획득자가 빈 큐를 볼 수 있다. Semaphore의 CAS만으로 객체 큐의 구조 변경까지 안전해지는 것은 아니다.
- Lease 생성자를 외부에 공개하지 않아서 다른 풀로 반환하는 API 자체가 없다. CAS에 성공한 첫 close만 반환하므로 중복 close로 수량이 부풀지 않는다. `get`은 이미 반환된 lease 사용을 거부한다.

## 예외·종료 정책을 어디까지 구현했나

시간 초과/인터럽트는 Semaphore 획득에서 종료되어 객체를 제거하지 않는다. 획득과 `close`가 경합하면 모니터 안에서 closed를 다시 검사하고 permit을 돌려준 뒤 실패한다. `close`는 신규 대여를 막고 idle 참조를 지운다. 이미 빌린 객체를 강제로 빼앗지 않으며 나중에 반환되면 큐에 넣지 않는다. 닫기 전에 기다리던 스레드는 반환이나 자신의 timeout까지 기다릴 수 있다. 즉시 종료 통지는 이 작은 구현에 없다.

이 풀의 대상은 StringBuilder 같은 일반 객체다. **물리 연결의 close, 유효성 검사, 상태 초기화, 생성 실패와 재시도, idle eviction은 구현하지 않았다.** AutoCloseable인 T를 넣어도 물리 close를 대신 호출하지 않는다. 데모에서 `same object=true`, `state left behind=previous borrower`가 출력되는 이유는 같은 객체를 상태 그대로 다시 주기 때문이다. 다음 Commons Pool 단계가 이 생명주기 공백을 채운다.

`Lease.get()`으로 꺼낸 참조는 반환 후에도 Java 참조로 남아 있다. get의 반환 검사만으로 이미 유출된 참조를 무효화할 수 없다. 같은 lease를 한 스레드에서 쓰고 다른 스레드가 동시에 닫는 것도 지원하지 않는다. CAS는 중복 반환을 막는 장치이지 사용 중 객체를 보호하는 락이 아니다. 호출자는 try-with-resources 범위 안에서만 사용해야 한다.

## JDK 내부와 연결

permit 내부 경로는 [이전 단계](../semaphore/README.md)를 따른다. `AtomicBoolean.compareAndSet`은 VarHandle CAS로 한 반환자만 선출한다([JDK 21u 소스](https://github.com/openjdk/jdk21u/blob/jdk-21.0.11-ga/src/java.base/share/classes/java/util/concurrent/atomic/AtomicBoolean.java)). `synchronized`는 큐와 closed의 가시성을 보장한다. 이 구현은 대여자 fair, 객체 FIFO이고, 이 두 정책은 독립적이다.

테스트는 24개 가상 스레드가 2개 객체를 2,400번 빌리는 동안 동일 객체가 동시에 대여되지 않음을 검사하고, 중복 close·timeout·종료 후 반환·참조 중복 입력도 검증한다. 이것은 JMH 처리량 측정이나 모든 스케줄의 정형 증명은 아니다.
