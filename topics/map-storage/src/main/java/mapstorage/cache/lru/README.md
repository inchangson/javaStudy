# 3. LinkedHashMap으로 정확한 LRU 만들기

## 실행

`LruDemo.main`: [A, B] → [B, A] → [A, C]. 왼쪽이 가장 오래 사용하지 않은 키다.
`./gradlew :topics:map-storage:test --tests 'mapstorage.cache.lru.*'`는 조회·교체에 따른
순서, 용량, 스냅샷 독립성, 동시 접근과 반복자 무효화를 확인한다.

## put 호출 경로

[LruCache.java](LruCache.java)의 put → HashMap.put → putVal → newNode 경로로 들어간다.
[LinkedHashMap 소스](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/util/LinkedHashMap.java)의
newNode가 오버라이드되어 해시 버킷 노드를 만드는 동시에 linkNodeAtEnd로 순서 리스트에 연결한다.
즉 버킷 탐색용 연결과 접근 순서용 before/after 연결은 역할이 다르다.

[HashMap 소스](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/util/HashMap.java)의
putVal은 새 노드 삽입 뒤 afterNodeInsertion(evict)을 호출한다.
LinkedHashMap.afterNodeInsertion은 head를 eldest로 잡고 사용자 정의 removeEldestEntry를 부른다.
우리 조건은 `size() > capacity`이다. 삽입 후 크기를 보므로 `>=`로 쓰면 하나 일찍 제거한다.
참이면 removeNode로 버킷에서 제거하고 afterNodeRemoval이 순서 연결도 끊는다.
기존 키의 값 교체는 새 삽입 경로가 아니라 afterNodeAccess로 가며 최근 사용 순서가 바뀐다.

## get 호출 경로

LruCache.get → LinkedHashMap.get → HashMap.getNode → LinkedHashMap.afterNodeAccess.
생성자의 세 번째 인자 true가 accessOrder를 켠다. 찾은 노드가 tail이 아니면
before/after를 연결 해제하고 마지막으로 옮긴 뒤 modCount를 증가시킨다.
이미 tail인 키를 읽으면 이동이 필요 없고, 없는 키를 읽어도 순서는 그대로다.

`accessOrderGetInvalidatesAnExistingIterator`는 한 스레드에서 반복자를 만든 뒤 A를 읽는다.
반복자의 expectedModCount와 바뀐 modCount가 달라 next에서 예외가 난다.
이 관찰은 get도 구조 변경을 할 수 있음을 보여 준다. 예외 발생을 동시성 안전장치로 쓰면 안 된다.

## 왜 get에도 synchronized인가

get 두 개가 동시에 서로 다른 노드를 이동하면 같은 순서 리스트를 수정한다.
LruCache는 get/put/size/keysOldestFirst 모두 동일 인스턴스 모니터를 사용한다.
내부 맵과 살아 있는 keySet을 노출하지 않고 잠금 안에서 만든 불변 목록을 반환한다.
따라서 관찰 목록을 읽는 동안 이후 캐시 변경이 그 목록을 바꾸지 않는다.
하지만 get 다음 put처럼 API 여러 개를 묶은 작업까지 원자적이라는 뜻은 아니다.

정확한 전역 순서를 유지하려면 이 구현에서는 조회도 서로 기다린다.
다음 Caffeine에서는 조회 데이터와 정책용 접근 기록을 분리하는 경로를 살펴본다.
