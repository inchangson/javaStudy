# 1. HashMap: 코드를 한 줄씩 따라가기

## 0. 이번에 확인할 질문

1. hashCode만 같으면 기존 값이 교체될까?
2. equals만 true면 기존 값이 교체될까?
3. 둘 다 같으면 새 객체여도 기존 값이 교체될까?
4. 넣었던 객체의 id를 바꾸면 왜 get은 null인데 size는 1일까?

[HashMapDemo.java](HashMapDemo.java)의 모든 키를 `static final class`로 통일했다.
`record`의 자동 생성 메서드 없이 `hashCode()`와 `equals()`를 직접 비교한다.
`final class`는 상속 금지이고, 객체 불변을 뜻하지 않는다. 앞의 세 키는 필드가 final이고,
`MutableKey`만 id 변경 메서드가 있다.

| 비교할 객체 | hashCode 비교 | equals 비교 | 두 번째 put 반환 | size |
| --- | --- | --- | --- | --- |
| `HashCodeCollisionKey(1)`, `(2)` | 7 == 7 | false | null | 2 |
| `EqualsCollisionKey(1, 1)`, `(1, 17)` | 1 != 17 | true | null | 2 |
| `HashCodeEqualsCollisionKey(1)`, `(1)` | 1 == 1 | true | first | 1 |

**두 번째 행은 equals/hashCode 계약을 일부러 위반한 실험이다.**
정상적인 키는 `a.equals(b)`가 true면 hashCode도 같아야 한다. 반대 방향은 필수가 아니다.
여기서 `EqualsCollisionKey`는 equals가 true인 두 객체를 비교하려는 학습용 이름이다.
보통은 이를 “equals 충돌”이라고 부르지 않고 “동등한 키”라고 한다.
`HashCodeEqualsCollisionKey` 역시 정상적인 동등 키의 값 교체를 보여준다.
계약 위반 키의 결과를 모든 Map 구현에 대한 보장으로 받아들이면 안 된다.

```bash
./gradlew :topics:map-storage:runDemo -Pdemo=mapstorage.hashmap.HashMapDemo
./gradlew :topics:map-storage:test --tests 'mapstorage.hashmap.*'
```

## 1. 예제의 키 구현부터 읽기

세 클래스의 equals는 자기 클래스인지 확인하고 id를 비교한다.
아래는 `HashCodeEqualsCollisionKey`에서 가져온 식이다.

```java
return other instanceof HashCodeEqualsCollisionKey key && id == key.id;
// other가 해당 타입이면 key라는 이름으로 사용한다.
// && 왼쪽이 false면 오른쪽은 평가하지 않는다. null도 instanceof에서 false다.
// 같은 타입인 경우 두 객체의 id가 같아야 true다.
```

| 클래스 | hashCode 본문 | equals에서 비교 | 실험의 차이 |
| --- | --- | --- | --- |
| HashCodeCollisionKey | `return 7;` | id | 다른 id도 같은 hash |
| EqualsCollisionKey | `return hash;` | id | 같은 id에 다른 hash를 직접 지정 |
| HashCodeEqualsCollisionKey | `return id;` | id | 같은 id라면 hash와 equals 모두 같음 |
| MutableKey | `return id;` | id | 삽입 뒤 id 변경 가능 |

`HashCodeEqualsCollisionKey`와 `MutableKey`의 비교 방식은 같다.
차이는 **삽입 뒤 비교에 쓰는 상태를 바꿀 수 있느냐**다.
서로 다른 클래스끼리 equals를 비교하는 실험은 아니다.

## 2. Node: HashMap이 엔트리 하나를 보관하는 상자

아래 JDK 발췌와 주석은
[OpenJDK jdk-21+35 HashMap.java](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/util/HashMap.java)를 기준으로 한다.
발췌에 한국어 설명 주석을 추가했다. Node는 필요한 필드와 생성자만 발췌했다.
설치된 JDK 패치 버전에 따라 줄 번호가 달라지므로 메서드 이름으로 찾아간다.

```java
static class Node<K,V> implements Map.Entry<K,V> {
    // K는 키 타입, V는 값 타입. Map.Entry는 키/값 한 쌍을 나타내는 인터페이스다.
    final int hash;      // 삽입 시 계산한 hash(key) 숫자. 이후 바뀌지 않는다.
    final K key;         // 전달받은 키 객체의 참조. 키 객체의 복사본이 아니다.
    V value;             // 값. 같은 키로 put하면 이 필드를 교체한다.
    Node<K,V> next;      // 같은 버킷의 다음 노드. 마지막이면 null이다.

    Node(int hash, K key, V value, Node<K,V> next) {
        this.hash = hash;   // 계산해 전달받은 int를 저장한다.
        this.key = key;     // 원래 키 객체를 가리킨다.
        this.value = value; // 전달받은 값을 저장한다.
        this.next = next;   // 다음 노드의 참조를 저장한다.
    }
}
```

`final K key`는 다른 객체로 참조를 바꿀 수 없다는 뜻이다.
그 객체 내부의 `id`까지 고정해 주지는 않는다.
Node의 `hash` 필드와 Node 자신의 `hashCode()` 메서드도 구분하자.
여기서 탐색에 쓰는 것은 **저장된 hash 필드**다.

```text
HashMap
  table: Node 참조를 담는 배열
    [0] → null
     …
    [7] → Node(hash=7, key=id 1, value="first", next=…)
              → Node(hash=7, key=id 2, value="second", next=null)
     …
```

버킷은 이 배열의 한 칸에 연결된 노드 묶음이다.
`table.length`는 버킷 수이고 `size`는 전체 엔트리 수다.
`newNode(...)`는 일반 HashMap에서는 `new Node<>(...)`를 반환하는 생성용 메서드다.

## 3. put → hash: 키와 계산한 숫자를 함께 넘긴다

```java
public V put(K key, V value) {
    // hash(key)를 먼저 계산한다. 키 참조와 값도 별도 인자로 전달한다.
    // false는 onlyIfAbsent: 기존 값이 있어도 교체한다.
    // true는 evict: 일반 삽입 모드의 후처리에 전달한다.
    return putVal(hash(key), key, value, false, true);
}
```
```java
static final int hash(Object key) {
    // 원래 hashCode 결과를 잠시 담을 지역 변수다.
    int h;
    // null 키의 hash는 0이다. 아니면 hashCode()를 호출한다.
    // >>> 16은 상위 비트를 아래로 옮기고 빈자리를 0으로 채운다.
    // ^는 XOR이다. 상위 비트 정보가 낮은 비트에도 반영되게 섞는다.
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
}
```

이번 숫자 1, 2, 7, 17은 `h >>> 16`이 0이어서 hashCode와 내부 hash가 같다.
일반적으로 둘을 같은 것으로 취급하면 안 된다.

## 4. putVal 전체: 각 실행 줄 바로 위의 주석을 읽자

지역 변수부터 구분하자.

| 변수 | 의미 |
| --- | --- |
| tab | table 배열 참조 |
| n | 배열 길이 |
| i | 이번에 접근할 버킷 인덱스 |
| p | 현재 확인 중인 노드, 처음에는 버킷의 첫 노드 |
| e | 다음 노드 또는 교체할 기존 노드. 새 삽입 경로에서는 null |
| k | 비교 중인 기존 키 참조 |
| binCount | 리스트를 따라가는 반복 횟수, 맵 전체 size가 아님 |

```java
final V putVal(int hash, K key, V value, boolean onlyIfAbsent,
               boolean evict) {
    // 이번 호출에서 사용할 지역 변수를 선언한다.
    Node<K,V>[] tab; Node<K,V> p; int n, i;
    // table을 tab에 대입한 뒤 미할당인지 검사한다. ||는 왼쪽이 true면 오른쪽을 생략한다.
    if ((tab = table) == null || (n = tab.length) == 0)
        // 최초 삽입이라면 resize로 배열을 만든다. 기본 생성자의 첫 배열은 16칸이다.
        n = (tab = resize()).length;
    // (n - 1) & hash로 인덱스 i를 정하고 첫 노드를 p에 담는다. 비었는지 검사한다.
    if ((p = tab[i = (n - 1) & hash]) == null)
        // 빈 버킷이면 새 Node 하나를 연결한다. key와 계산한 hash가 함께 저장된다.
        tab[i] = newNode(hash, key, value, null);
    // 앞의 조건에 해당하지 않는 경로로 들어간다.
    else {
        // 기존 노드를 찾은 결과와 비교할 키를 담을 변수를 선언한다.
        Node<K,V> e; K k;
        // 첫 노드에 저장된 hash와 이번에 계산한 hash를 먼저 비교한다.
        if (p.hash == hash &&
            // hash가 같을 때만 실행한다. 같은 참조이거나 equals가 true면 같은 키다.
            // ==가 true라면 || 뒤의 equals 호출도 생략된다.
            ((k = p.key) == key || (key != null && key.equals(k))))
            // 첫 노드가 같은 키다. e에 담아 아래의 값 교체 구간으로 간다.
            e = p;
        // 첫 노드가 같은 키가 아니고, 버킷이 트리 형태인지 검사한다.
        else if (p instanceof TreeNode)
            // 트리에서 탐색/삽입한다. 기존 키를 찾으면 그 노드, 새로 삽입하면 null을 돌려준다.
            e = ((TreeNode<K,V>)p).putTreeVal(this, tab, hash, key, value);
        // 앞의 조건에 해당하지 않는 경로로 들어간다.
        else {
            // 일반 리스트라면 다음 노드를 반복해서 확인한다. 종료는 안쪽 break로 한다.
            for (int binCount = 0; ; ++binCount) {
                // 다음 노드를 e에 담는다. null이면 리스트 끝까지 같은 키를 못 찾았다.
                if ((e = p.next) == null) {
                    // 끝에 새 노드를 붙인다. 지역 변수 e는 여전히 null이다.
                    p.next = newNode(hash, key, value, null);
                    // 충돌 리스트가 충분히 길면 트리 전환을 요청한다. 상수 값은 8이다.
                    if (binCount >= TREEIFY_THRESHOLD - 1) // -1 for 1st
                        // 배열이 64칸보다 작으면 트리 전환 대신 우선 배열을 확장한다.
                        treeifyBin(tab, hash);
                    // 현재 반복문을 끝낸다. 새 삽입이면 e는 null, 기존 키 발견이면 e는 그 노드다.
                    break;
                }
                // 다음 노드도 저장된 hash부터 비교한다.
                if (e.hash == hash &&
                    // hash가 같을 때만 참조 동일성 또는 equals로 키를 판정한다.
                    ((k = e.key) == key || (key != null && key.equals(k))))
                    // 현재 반복문을 끝낸다. 새 삽입이면 e는 null, 기존 키 발견이면 e는 그 노드다.
                    break;
                // 아직 못 찾았으므로 현재 위치를 다음 노드로 옮긴다.
                p = e;
            }
        }
        // 기존 키를 찾은 경우다. 새 노드를 붙였다면 이 구간을 건너뛴다.
        if (e != null) { // existing mapping for key
            // 교체 전 값을 보관한다.
            V oldValue = e.value;
            // 일반 put은 onlyIfAbsent=false이므로 교체 조건을 만족한다.
            if (!onlyIfAbsent || oldValue == null)
                // 기존 Node의 value만 바꾼다. key 참조와 저장된 hash는 그대로다.
                e.value = value;
            // 일반 HashMap에서는 빈 후처리다. LinkedHashMap 등에서 확장한다.
            afterNodeAccess(e);
            // 교체 전 값을 반환하고 메서드를 끝낸다. 아래 size 증가는 실행하지 않는다.
            return oldValue;
        }
    }
    // 새 엔트리를 추가한 구조 변경을 기록한다. 반복자의 변경 감지 등에 사용한다.
    ++modCount;
    // 새 엔트리 수를 1 늘리고 확장 임계값을 넘었는지 확인한다.
    if (++size > threshold)
        // 임계값을 넘으면 배열을 확장한다.
        resize();
    // 삽입 후처리다. 일반 HashMap에서는 비어 있다.
    afterNodeInsertion(evict);
    // 새 키를 삽입했으므로 교체 전 값이 없어서 null을 반환한다.
    return null;
}
```

`put`의 null 반환만으로 항상 새 삽입이라고 판정할 수는 없다.
기존 값이 null이었다면 교체해도 null을 반환한다. 이번 실험은 모든 값이 문자열이어서 구분된다.

### 세 예제를 위 코드에 대입하기

**A. HashCodeCollisionKey: hash만 같음**

```text
put(key id=1, "first")
  hash=7 → table[7] 비어 있음 → 새 Node → size=1
put(key id=2, "second")
  hash=7 → table[7]의 첫 노드 발견
  p.hash == hash → 7 == 7 → true
  p.key == key → 서로 다른 객체 → false
  key.equals(p.key) → id 2 != id 1 → false
  트리 아님 → p.next가 null → 다음 Node 추가
  e는 null → 교체 구간 건너뜀 → size=2 → null 반환
```

**B. EqualsCollisionKey: equals만 true — 계약 위반 실험**

hash를 1과 17로 정한 이유는 같은 버킷에서도 hash 비교가 먼저라는 점을 보기 위해서다.

```text
배열 길이 16 → n - 1 = 15
15 & 1  = 1
15 & 17 = 1  → 서로 다른 hash라도 같은 버킷일 수 있다!

put(key id=1/hash=1, "first") → table[1]에 Node 생성
put(key id=1/hash=17, "second")
  table[1]의 첫 노드 발견
  p.hash == hash → 1 == 17 → false
  && 단락 평가 때문에 참조 비교와 equals는 실행하지 않음
  다음 노드 없음 → 새 Node 연결 → size=2 → null 반환
```

직접 `first.equals(second)`를 부르면 true다.
그러나 이 put 경로에서는 equals를 호출하기 전에 hash 조건에서 탈락한다.
**같은 버킷, 같은 hash, 동등한 키는 각각 다른 조건이다.**

**C. HashCodeEqualsCollisionKey: 둘 다 같음**

```text
put(첫 번째 key id=1, "first") → Node 생성, size=1
put(두 번째 key id=1, "updated")
  p.hash == hash → 1 == 1 → true
  p.key == key → 서로 다른 객체 → false
  key.equals(p.key) → id 1 == id 1 → true
  e = p
  oldValue = "first"
  e.value = "updated"
  return oldValue → "first" 반환, size는 여전히 1
```

처음 질문의 size가 2였던 이유는 별도로 id=2인 키도 넣었기 때문이다.
여기서는 교체만 분리해서 보기 위해 id=1인 두 객체만 넣었다.

## 5. get → getNode: 이번에는 현재 키로 hash를 계산한다

```java
public V get(Object key) {
    // 찾은 노드를 받을 변수다.
    Node<K,V> e;
    // getNode가 null이면 null을, 노드를 찾았다면 그 value를 돌려준다.
    return (e = getNode(key)) == null ? null : e.value;
}
```
```java
final Node<K,V> getNode(Object key) {
    // 배열, 첫 노드, 순회 노드, 배열 길이, 이번 조회 hash, 비교할 키다.
    Node<K,V>[] tab; Node<K,V> first, e; int n, hash; K k;
    // 배열이 있고 길이가 양수여야 다음 조건을 평가한다.
    if ((tab = table) != null && (n = tab.length) > 0 &&
        // 현재 key로 hash를 새로 계산한다. 해당 버킷의 첫 노드가 없으면 탐색 종료다.
        (first = tab[(n - 1) & (hash = hash(key))]) != null) {
        // 저장 당시 hash와 현재 조회 hash부터 비교한다.
        if (first.hash == hash && // always check first node
            // hash가 같아야 같은 참조인지 또는 equals가 true인지 확인한다.
            ((k = first.key) == key || (key != null && key.equals(k))))
            // 첫 노드가 일치하면 바로 반환한다.
            return first;
        // 첫 노드가 일치하지 않았다면 다음 노드가 있는지 확인한다.
        if ((e = first.next) != null) {
            // 트리로 구성된 버킷인지 확인한다.
            if (first instanceof TreeNode)
                // 트리 탐색에 이번 hash와 키를 넘기고 결과를 반환한다.
                return ((TreeNode<K,V>)first).getTreeNode(hash, key);
            // 일반 리스트는 두 번째 노드부터 하나씩 확인한다.
            do {
                // 여기서도 저장된 hash를 먼저 비교한다.
                if (e.hash == hash &&
                    // 그 뒤에 참조 동일성 또는 equals를 확인한다.
                    ((k = e.key) == key || (key != null && key.equals(k))))
                    // 일치하는 노드를 반환한다.
                    return e;
            // 다음 노드로 이동한다. 끝에 도달하면 반복을 멈춘다.
            } while ((e = e.next) != null);
        }
    }
    // 선택한 버킷에서 못 찾았다. 다른 버킷을 전부 뒤지지 않는다.
    return null;
}
```

### MutableKey를 한 줄씩 추적하기

```java
var key = new MutableKey(1);           // 객체 A: id=1
var map = new HashMap<MutableKey, String>(); // table은 아직 null, size=0
map.put(key, "stored");               // hash=1 계산 → table[1]에 Node 생성
key.changeId(2);                      // 객체 A의 id만 2로 변경
System.out.println(map.get(key));     // 현재 hash=2 → table[2]는 비어 있음 → null
System.out.println(map.size());       // Node를 삭제한 적이 없으므로 1
```

```text
변경 전: table[1] → Node(hash=1, key=객체 A(id=1), value="stored")
변경 후: table[1] → Node(hash=1, key=객체 A(id=2), value="stored")
조회:    hash(A)=2 → table[2] → null
```

네 추론대로 **삽입 당시 hash는 Node에 고정되고, 조회 hash는 현재 객체로 다시 계산된다.**
이 예제에서는 버킷이 비어 있어서 키 비교까지 도달하지 않는다.
설령 id를 17로 바꿔 같은 버킷을 선택하게 해도 저장 hash 1과 조회 hash 17이 달라
`==` 비교까지 도달하지 못한다. “같은 객체니까 찾겠지”가 성립하지 않는 이유다.
테스트는 id를 1로 되돌리면 다시 조회된다는 점도 확인한다. 원인 확인용 실험이며,
실제 키 설계에서는 equals/hashCode에 사용하는 상태를 삽입 후 바꾸지 않는다.

## 6. TreeNode: 충돌 노드를 트리로 연결한 형태

처음 putVal을 읽을 때는 “같은 버킷을 리스트 대신 트리로 찾는 분기”로 이해하면 된다.
Node, TreeNode가 별도 저장소인 것은 아니다. 둘 다 HashMap 내부의 엔트리를 나타낸다.

```text
Node<K,V>                         hash, key, value, next
  └─ LinkedHashMap.Entry<K,V>      before, after 추가
       └─ HashMap.TreeNode<K,V>    parent, left, right, prev, red 추가
```

TreeNode는 상속으로 Node의 hash/key/value/next를 갖는다.
`parent`는 부모, `left/right`는 자식, `red`는 레드-블랙 트리의 색 정보다.
`prev`는 버킷 내 이전 노드다. 트리 노드도 next/prev 연결을 함께 유지한다.
상속에 LinkedHashMap.Entry가 등장해도 HashMap 자체가 접근 순서를 관리한다는 뜻은 아니다.

putVal에서 만난 두 줄을 다시 읽자.

```java
else if (p instanceof TreeNode) // 이 버킷이 트리 형태로 되어 있다면
    e = ((TreeNode<K,V>)p).putTreeVal(this, tab, hash, key, value);
    // this: 현재 맵, tab: 배열, hash/key/value: 이번에 넣을 데이터
    // 기존 키가 있으면 그 노드를 반환해 putVal의 공통 교체 구간으로 보낸다.
    // 새 키이면 트리에 삽입하고 null을 반환해 putVal의 size 증가 구간으로 보낸다.
```

| 메서드 | 역할 | 이번에 볼 것 |
| --- | --- | --- |
| treeifyBin | 리스트 버킷을 트리로 바꿀지 결정 | 배열 길이가 64 미만이면 우선 resize |
| putTreeVal | 트리에서 기존 키 탐색 또는 새 노드 삽입 | 기존 노드/null 반환의 의미 |
| getTreeNode → find | 트리에서 조회 | 저장 hash와 키를 비교 |

초기 용량 64에서 동일 hash 키 9개를 넣는 테스트가 트리 전환을 관찰한다.
putVal에서 `binCount`가 0부터 시작하고 첫 노드를 따로 다루므로,
“상수가 8이니까 정확히 8번째 put에 무조건 트리화”라고 읽으면 안 된다.
동일 hash이고 Comparable도 아닌 키는 TreeNode.find에서 양쪽 가지를 찾아볼 수 있어
모든 조회가 무조건 로그 시간이라고 일반화하지 않는다.
이 트리는 메모리 내 충돌 처리용이며 DB의 페이지 기반 B-tree와는 다르다.

## 7. resize: 첫 배열 생성과 이후 확장

putVal에서 만난 두 호출을 구분하자.

```java
n = (tab = resize()).length; // 첫 삽입: 없던 table 생성

if (++size > threshold)     // 새 키를 추가한 뒤 임계값 초과 여부 확인
    resize();              // 이미 있는 table 확장
```

기본 생성자의 첫 배열은 16칸, 기본 부하율은 0.75여서 임계값은 12다.
서로 다른 13번째 키가 들어오면 32칸으로 확장한다.
같은 키의 값 교체는 이 size 증가 구간에 도달하지 않는다.

확장 중 리스트 노드의 재배치는 아래 식으로 갈린다. 다음은 흐름을 풀어 쓴 의사 코드다.

```java
if ((node.hash & oldCap) == 0) // 기존 노드에 저장된 hash의 해당 비트를 확인
    keepAt(oldIndex);         // 기존 버킷 인덱스에 남을 묶음에 연결
else
    moveTo(oldIndex + oldCap); // 이전 용량만큼 이동할 묶음에 연결
```

키 객체의 hashCode를 다시 호출해 모든 hash를 갱신하는 과정이 아니다.
resize가 가변 키 문제를 자동으로 고쳐 주지도 않는다.

## 8. 디버거로 직접 확인하기

1. Demo의 `hashCodeCollision()`에서 시작하고 JDK `HashMap.put`에 Step Into한다.
2. `hash` → `putVal` → `newNode` → `Node` 생성자에서 hash/key/value를 확인한다.
3. 두 번째 put에서 `p.hash == hash`와 `key.equals(k)` 결과를 따로 확인한다.
4. `equalsCollision()`에서는 같은 버킷인데 hash 비교에서 탈락하는지 확인한다.
5. `hashCodeEqualsCollision()`에서는 `e.value = value` 후 size 증가 전에 반환하는지 확인한다.
6. `mutableKey()`에서는 Node.hash=1, Node.key.id=2를 보고 getNode의 조회 hash=2와 비교한다.
7. 리사이즈/트리화는 테스트 메서드에 중단점을 두고 각각 `resize`, `treeifyBin`으로 들어간다.

테스트의 배열 길이와 TreeNode 검사는 리플렉션을 사용한 OpenJDK 구현 관찰이다.
공개 Map 계약과 구분한다. 필요한 `--add-opens`는 Gradle 테스트 설정에 있다.

## 다음에 같이 답해 볼 질문

`p.hash == hash && ((k = p.key) == key || (key != null && key.equals(k)))`에서
hash가 다르면 왜 같은 참조인지를 확인하는 `==`도 실행하지 않을까?

이 흐름이 익숙해지면 다음 패키지에서 두 스레드의 `get → +1 → put`이
스레드 안전한 Map만으로 안전해지는지 살펴본다.
