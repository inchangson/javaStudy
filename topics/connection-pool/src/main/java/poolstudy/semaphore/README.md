# 01. Semaphore: 개수는 지켜도 객체는 관리하지 않는다

## 실행과 관찰

```bash
./gradlew :topics:connection-pool:runDemo
./gradlew :topics:connection-pool:test --tests 'poolstudy.semaphore.*'
```

`SemaphoreDemo.main → withPermit`는 1개 permit 중 하나를 획득한다. 출력은 `inside: available=0`, 반환 후 `1`, 잘못된 추가 반환 후 `2`다. 마지막 줄은 의도한 반례다. Semaphore는 초기 개수를 상한으로 기억하거나, 누가 빌렸는지 검사하지 않는다. 따라서 이것만으로 연결 풀을 구현할 수 없다.

## 실제 JDK 호출부를 따라가기

읽은 구현은 로컬 Corretto **21.0.11**의 `lib/src.zip` 안 `java.base/java/util/concurrent/{Semaphore,locks/AbstractQueuedSynchronizer}.java`다. 온라인 대조: [OpenJDK 21u Semaphore](https://github.com/openjdk/jdk21u/blob/jdk-21.0.11-ga/src/java.base/share/classes/java/util/concurrent/Semaphore.java), [AQS](https://github.com/openjdk/jdk21u/blob/jdk-21.0.11-ga/src/java.base/share/classes/java/util/concurrent/locks/AbstractQueuedSynchronizer.java). 아래는 원문 복사가 아닌 호출 경로 해설이다.

1. `withPermit`의 시간 제한 있는 `tryAcquire(timeout, NANOSECONDS)`는 `sync.tryAcquireSharedNanos(1, nanosTimeout)`를 호출한다. AQS는 인터럽트 여부를 검사하고 `tryAcquireShared`를 먼저 시도한다.
2. `new Semaphore(1, true)`는 `FairSync`를 만든다. `FairSync.tryAcquireShared`는 `hasQueuedPredecessors()`가 참이면 실패한다. 아니면 `state - acquires`가 음수가 아닌지 확인하고 `compareAndSetState`로 permit을 감소시킨다. false 설정은 `NonfairSync → nonfairTryAcquireShared`여서 선행 대기자 검사를 생략한다.
3. 즉시 획득 실패 시 AQS의 shared 모드 `acquire` 경로에서 노드를 대기열에 넣고 `LockSupport.parkNanos`로 남은 시간만 기다린다. 깨었다고 획득한 것이 아니다. 다시 획득 조건을 검사한다. 시간 초과는 false, 인터럽트는 `InterruptedException`으로 돌아온다.
4. 작업 이후 `finally → Semaphore.release → AQS.releaseShared → Sync.tryReleaseShared`는 CAS로 state를 더한다. AQS는 후속 대기 노드를 깨워 재시도하게 한다. 반환한 스레드가 획득한 스레드였는지는 검사하지 않는다.
5. 주의: 인자 없는 `tryAcquire()`는 **fair 설정에서도** `nonfairTryAcquireShared`로 바로 간다. 공정한 시간 제한 시도를 원하면 시간 인자 버전을 써야 한다. 공정성은 스케줄러의 실행 순서 보장이나 처리량 개선 약속이 아니다.

## 예외 경계와 메모리 가시성

`try`를 획득 성공 **뒤**에 둔 이유가 핵심이다. 획득이 실패했는데 finally에서 release하면 존재하지 않던 permit이 생긴다. 테스트는 작업 예외에는 반환되고, timeout/interrupt에는 반환되지 않음을 검증한다. InterruptedException 발생 시 인터럽트 플래그는 해제된다. 호출자가 여기서 예외를 전파하지 않고 처리한다면 취소 정책에 맞춰 플래그를 복구해야 한다.

한 스레드의 release 이전 작업은 다른 스레드가 이후 성공한 acquire 뒤에서 관찰할 수 있다(happens-before). 하지만 어떤 객체를 어느 스레드에게 전달할지, 중복 반환인지, 닫힌 연결인지까지 해결하지 않는다. 다음 단계에서는 객체를 담는 큐와 한 번만 반납 가능한 lease가 필요하다.

## 직접 확인할 질문

- permits=1인데 다른 스레드가 release를 두 번 하면 왜 3이 될 수 있을까?
- timeout을 작업 전체의 제한으로 생각하면 왜 틀릴까? 여기서는 획득에만 적용된다.
- fair 설정과 객체 큐 FIFO/LIFO는 같은 정책일까? 하나는 대기자의 순서, 다른 하나는 유휴 객체 선택 순서다.
