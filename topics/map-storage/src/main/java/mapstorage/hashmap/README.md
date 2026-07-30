# 1. HashMap: put 한 번에서 시작하기

## 실행과 관찰

`HashMapDemo.main`을 실행하면 교체 전 값 first, 서로 다른 키 수 2,
변경한 키의 조회 결과 null, 남아 있는 엔트리 수 1을 확인한다.
`./gradlew :topics:map-storage:test --tests 'mapstorage.hashmap.*'`는
이 결과뿐 아니라 기본 테이블 16 → 32 확장과 충돌 버킷의 트리 전환을 확인한다.
리플렉션 검증은 Java Map 계약이 아니라 지정한 JDK 구현을 관찰하는 실험이다.

## 실제 호출 경로

[OpenJDK 21 HashMap 소스](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/util/HashMap.java)
에서 `put`, `hash`, `putVal`, `resize`, `getNode`, `treeifyBin`에 중단점을 둔다.

1. `collisions.put(key, value)`는 `putVal(hash(key), key, value, false, true)`로 간다.
   `hash`는 hashCode의 상위 비트를 하위 비트와 XOR한다. hashCode 자체가 같으면
   이 처리 후에도 충돌한다. 이 함수는 서로 다른 키를 유일한 정수로 만드는 함수가 아니다.
2. `putVal`은 table이 없으면 `resize`로 최초 할당한다. 기본 생성자는 처음부터
   16칸 배열을 만들지 않는다. 배열 길이 n에서 `(n - 1) & hash`가 버킷을 고른다.
3. 버킷이 비어 있으면 `newNode`로 연결한다. 기존 노드가 있으면 저장된 hash를 비교하고,
   키 참조 동일성 또는 equals를 검사한다. CollisionKey(1), CollisionKey(2)는
   hash가 같지만 equals가 다르므로 같은 버킷의 별도 노드다.
4. 같은 키를 찾으면 기존 value를 교체하고 oldValue를 반환한다. 이 경로는 size를
   증가시키지 않는다. 테스트가 반환값과 size를 함께 검사하는 이유다.
5. 새 노드 삽입 뒤 `++size > threshold`면 resize한다. 기본 부하율 0.75에서
   용량 16의 임계값은 12이고 13번째 서로 다른 키가 확장을 일으킨다.
6. `resize`는 기존 노드의 저장된 hash에서 `hash & oldCap`을 확인한다.
   리스트는 기존 인덱스에 남는 묶음과 oldCap만큼 이동하는 묶음으로 나뉜다.
   키의 hashCode를 다시 불러 새 해시를 계산하는 과정이 아니다.

## 키를 변경하면 왜 못 찾는가

`map.get(key)` → `getNode(key)`는 현재 키에서 hash를 다시 계산한다.
삽입 당시 노드에는 hash=1이 저장돼 있는데 key.changeId(2) 뒤에는 조회 hash=2다.
다른 버킷을 보게 되며, 저장 hash 비교도 통과하지 못한다. 같은 인스턴스라는 이유로
맵 전체를 검색해 주지 않는다. size=1이라는 관찰은 삭제가 아니라 탐색 실패임을 보여 준다.
테스트의 원상 복구는 원인 확인용이다. 실제 키는 equals/hashCode에 쓰는 상태를 불변으로 둔다.

## 충돌이 길어질 때

리스트 삽입의 `binCount >= TREEIFY_THRESHOLD - 1` 분기에서 treeifyBin을 호출한다.
배열이 MIN_TREEIFY_CAPACITY(64)보다 작으면 우선 resize한다. 테스트는 초기 용량을
64로 주고 동일 hash 키 9개를 삽입해 트리 경로를 직접 관찰한다.
`TreeNode.find`의 비교 경로도 읽어 보자. 같은 hash이고 Comparable도 아닌 키는
탐색 시 양쪽 가지를 확인할 수 있어 무조건 로그 시간이라고 일반화하지 않는다.
이 트리는 메모리 버킷의 충돌 처리 구조이며 DB의 페이지 기반 B-tree와 같은 구조가 아니다.

## 다음 질문

여기서는 한 스레드만 쓴다. 두 스레드가 같은 값을 읽고 각각 +1을 저장하면,
맵 구현이 스레드 안전하다는 것만으로 두 증가가 모두 보존될까?
