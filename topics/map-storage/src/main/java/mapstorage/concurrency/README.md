# 2. 스레드 안전한 메서드와 원자적인 작업

## 실행

`CounterDemo.main`의 결과는 1, 1, 2000, 2000이다.
`./gradlew :topics:map-storage:test --tests 'mapstorage.concurrency.*'`로 확인한다.
CyclicBarrier가 두 읽기가 끝날 때까지 쓰기를 막으므로 우연히 경쟁이 일어나길 기다리지 않는다.
이 barrier를 compute 콜백이나 동일한 외부 잠금 안으로 옮기면 다른 스레드가 진입하지 못해
교착될 수 있다. 안전한 경로에는 barrier를 넣지 않는다.

## synchronizedMap의 호출부 따라가기

[Collections 소스](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/util/Collections.java)의
`synchronizedMap`은 원래 맵을 `SynchronizedMap`으로 감싼다.
`CounterDemo.lostUpdate` → 래퍼 `get` → `synchronized(mutex)` → 원래 HashMap.get,
그다음 별도의 래퍼 `put` → `synchronized(mutex)` → 원래 HashMap.put 경로다.
get이 반환되면 모니터를 놓는다. 두 스레드가 0을 읽고 각각 1을 쓰는 실행은
각 메서드의 잠금 규칙을 전혀 위반하지 않는다.

`incrementSafely(..., true)`는 get과 put 전체를 `synchronized(map)`으로 감싼다.
이 예제의 직접 생성한 래퍼는 mutex가 자기 자신이므로 내부 잠금과 같은 모니터이며 재진입 가능하다.
원본 HashMap을 외부에 노출하지 않고 모든 접근이 같은 규칙을 따라야 한다.
래퍼의 `compute` 역시 콜백 실행까지 mutex로 감싸므로 하나의 대안이다.
반복자는 별도로 사용자가 동기화해야 한다. 메서드 하나의 안전성에서 반복 전체의 안전성을 추론하지 말자.

## ConcurrentHashMap 호출부 따라가기

[ConcurrentHashMap 소스](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/util/concurrent/ConcurrentHashMap.java)의
`get`, `putVal`, `compute`에 중단점을 둔다.

1. get은 spread한 hash로 tabAt에서 버킷을 읽고 키를 찾는다. value/next의 volatile 필드와
   테이블 원소의 acquire 접근이 가시성에 관여한다. get+put 전체를 묶는 잠금은 없다.
2. put → putVal은 빈 버킷에서 CAS로 노드를 설치하고, 기존 일반 버킷에서는 머리 노드에
   synchronized한 뒤 버킷이 여전히 같은지 재확인한다. 리사이즈 중이면 이동을 돕는 경로도 있다.
   하나의 전역 모니터로 모든 키를 직렬화하는 구조가 아니다.
3. 이 예제는 count를 미리 넣는다. 따라서 compute의 기존 일반 버킷 분기에서 머리 노드를
   잠그고 키를 찾은 뒤 remappingFunction.apply와 value 대입을 그 범위 안에서 수행한다.
   같은 키의 두 증가 사이에 읽기와 쓰기가 분리되지 않으므로 2000이 된다.
4. 빈 버킷의 compute는 ReservationNode를 설치하는 별도 경로다. 콜백이 null을 반환하면
   기존 매핑은 삭제될 수 있다. 콜백은 짧게 유지하고 다른 매핑 갱신을 섞지 않는다.

## 두 키로 늘리면

`twoComputeCallsExposeIntermediateBalance`는 A 차감 뒤 작성 스레드를 latch에서 멈춘다.
읽는 스레드는 B 증가 전에 합계 1900을 보고, 완료 후에는 2000을 본다.
이 실험에서는 두 get 사이에도 쓰기가 없게 제어해 중간 상태임을 분명히 한다.
각 compute의 원자성과 이체 전체의 원자성은 다르다. 단순히 map을 복사한다고
동시에 바뀌는 맵의 원자적인 스냅샷을 얻는 것도 아니다.

다음에는 읽기가 내부 구조를 변경하는 LRU를 만들어 보고,
이후 storage.atomic에서 읽는 쪽까지 참여하는 공통 잠금으로 이체를 묶는다.
