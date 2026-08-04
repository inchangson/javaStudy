# 4. Caffeine: 데이터 조회와 정책 유지 작업 분리

## 실행과 버전

의존성은 Caffeine **3.1.8**로 고정한다. `CaffeineDemo.main`은 A=1,
정리 전 크기 3, 정리 후 크기 2를 출력한다.
`./gradlew :topics:map-storage:test --tests 'mapstorage.cache.caffeine.*'`로 검증한다.
ManualExecutor는 단일 스레드 실험 도구로, 작업을 버리지 않고 큐에 보관한다.
운영용 Executor 구현이 아니다. 기본 비동기 실행을 그대로 쓰면 maintenance 시점이 달라져
정리 전 크기가 항상 3이라는 결과를 보장할 수 없다.

## 생성부터 실제 구현 선택까지

[Caffeine.java](https://github.com/ben-manes/caffeine/blob/v3.1.8/caffeine/src/main/java/com/github/benmanes/caffeine/cache/Caffeine.java)의
maximumSize(2) → build → isBounded 경로를 따라간다. 크기 제한이 있으므로
BoundedLocalCache.BoundedLocalManualCache가 선택된다. 제한 없는 빌더의 구현과 구분한다.
옵션별 내부 캐시 클래스는 팩터리가 선택하지만 공통 알고리즘은 BoundedLocalCache에서 읽는다.

## 쓰기 경로: 데이터가 먼저 보인다

1. Demo의 cache.put → [LocalManualCache.put](https://github.com/ben-manes/caffeine/blob/v3.1.8/caffeine/src/main/java/com/github/benmanes/caffeine/cache/LocalManualCache.java)
   → cache().put → BoundedLocalCache.put으로 진입한다.
2. 새 키라면 nodeFactory.newNode로 노드를 만들고 내부 ConcurrentHashMap인 data의
   putIfAbsent로 게시한다. 경쟁으로 기존 노드가 발견되면 기존 값 처리 경로로 다시 간다.
3. 게시 성공 뒤 afterWrite(new AddTask(...))를 호출한다. AddTask는 정책 자료구조에
   노드를 연결하고 정책상 무게를 반영하는 작업이다. 데이터 삽입을 나중까지 미루는 큐가 아니다.
4. afterWrite는 writeBuffer.offer를 시도하고 scheduleAfterWrite로 정리를 예약한다.
   버퍼가 막히면 재시도하고 끝내 직접 evictionLock을 잡아 maintenance(task)를 수행한다.
   정상적인 처리 경로에서 쓰기 정책 작업을 단순히 버리고 반환하지 않는다.

[BoundedLocalCache 소스](https://github.com/ben-manes/caffeine/blob/v3.1.8/caffeine/src/main/java/com/github/benmanes/caffeine/cache/BoundedLocalCache.java)에서
put, afterWrite, AddTask.run에 중단점을 둔다. 실험의 3회 삽입은 버퍼를 포화시키지 않는다.
정리를 멈췄는데도 A를 읽을 수 있고 크기가 3인 이유를 data와 정책 큐로 나눠 설명해 보자.

## 읽기 경로: 모든 접근마다 전역 리스트를 옮기지 않는다

LocalManualCache.getIfPresent → BoundedLocalCache.getIfPresent → data.get으로 노드를 찾는다.
노드가 있어도 hasExpired가 참이면 null을 반환하고 정리를 예약한다.
유효한 노드에서는 필요에 따라 접근 시각을 갱신하고 afterRead로 들어간다.
따라서 ‘모든 읽기 관련 변경이 버퍼 안에서만 일어난다’고 이해하면 안 된다.

afterRead는 통계를 반영하고 readBuffer.offer를 시도한다. 초기 fastpath에서는
skipReadBuffer가 참이라 기록을 생략할 수도 있다. FULL 등의 결과는 정리 예약 판단에 쓰인다.
[BoundedBuffer.RingBuffer](https://github.com/ben-manes/caffeine/blob/v3.1.8/caffeine/src/main/java/com/github/benmanes/caffeine/cache/BoundedBuffer.java)는
공간 확인, write 위치 CAS, 배열 setRelease로 게시한다. 소비자는 getAcquire로 읽는다.
CAS 실패나 포화로 모든 읽기 기록이 보존되지 않을 수 있다. 그 손실은 교체 정책의
접근 이력 정확도에 영향을 주며, 이미 data에서 읽은 사용자 값을 임의로 손실시키는 일이 아니다.
상위 StripedBuffer가 경합을 분산하고 제한적으로 재시도하므로 단일 ring 시도와 전체 offer를 구분한다.

## cleanUp에서 합쳐지는 경로

cache.cleanUp → 내부 cleanUp → performCleanUp → evictionLock → maintenance.
maintenance는 drainReadBuffer, drainWriteBuffer를 처리하고 만료·용량 퇴출을 수행한다.
읽기 기록 소비는 onAccess로 이어져 frequencySketch와 window/probation/protected 순서를 갱신한다.
단순한 정확한 LRU와 달라서 ‘A를 읽었으니 무조건 B가 퇴출된다’는 테스트를 만들지 않는다.
명시적 정리 후 한도가 맞는지는 이 단일 스레드 실험에서 검증한다.

## 만료와 로딩 실험

가짜 ticker로 4초에는 값이 있고 5초에는 null임을 검사한다. sleep에 의존하지 않는다.
만료된 노드는 아직 크기에 포함될 수 있지만 getIfPresent의 hasExpired 분기에서 숨겨진다.
cleanUp 뒤에는 물리 제거도 반영된다. estimatedSize만으로 읽을 수 있는 값 수를 추론하지 않는다.

cache.get(key, mappingFunction)은 LocalManualCache.get → cache().computeIfAbsent 경로다.
테스트는 같은 키 재조회가 함수를 재실행하지 않으며 invalidate 후 다시 실행함을 확인한다.
invalidate는 LocalManualCache.invalidate → 내부 remove로 이어지고 RemovalTask로 정책 정리를 예약한다.
이 테스트는 동시 로딩 성능이나 전체 키에 대한 트랜잭션을 증명하지 않는다.

## 다음 질문

정책 큐는 JVM 메모리에 있다. 쓰기 기록을 정상 처리한다는 성질은 전원 차단 후 복구와 다르다.
두 키 갱신의 실패 처리는 storage.atomic, 프로세스 재시작은 storage.recovery에서 별도로 구현한다.
