# Java Map에서 저장소까지

Java 21. 각 소주제 커밋에서 해당 패키지의 README와 테스트를 함께 읽는다.

| 순서 | 패키지 | 질문 |
| --- | --- | --- |
| 1 | `hashmap` | 삽입한 키를 왜 못 찾을까? 충돌과 리사이즈는 어떻게 처리할까? |
| 2 | `concurrency` | 안전한 메서드 두 개를 조합하면 안전할까? |
| 3 | `cache.lru` | 조회가 왜 쓰기 작업이 될까? |
| 4 | `cache.caffeine` | 데이터와 교체 정책을 왜 분리할까? |
| 5 | `storage.atomic` | 두 키의 변경·관찰·실패를 어떻게 묶을까? |
| 6 | `storage.recovery` | 프로세스가 종료돼도 커밋을 어떻게 복구할까? |

## 이 과정을 공부하는 목적: 캐시에서 RDB 트랜잭션까지

이 과정은 메모리 Map에 요구사항을 하나씩 추가하며 “지금 구현이 무엇을 보장하지 못하는가”를
확인한다. 캐시 구현을 그대로 RDB로 바꾸는 과정은 아니다. 마지막에는 작은 저장소의
원자성·격리·복구 실험을 실제 RDB의 트랜잭션과 비교한다.

```text
키로 값을 찾는다 (HashMap)
  → 여러 스레드가 함께 바꾼다 (concurrency)
  → 보관할 양과 시간을 제한한다 (LRU, Caffeine)
  → 여러 키의 성공·실패와 관찰을 묶는다 (storage.atomic)
  → 프로세스가 종료돼도 커밋을 복구한다 (storage.recovery)
  → RDB의 트랜잭션·격리 수준·WAL과 비교한다
```

| HashMap에서 배운 내용 | 다음 공부와 연결되는 이유 | 이어서 읽기 |
| --- | --- | --- |
| 키의 동등성과 가변 키 문제 | 같은 요청은 같은 캐시 키로, 다른 데이터는 다른 키로 표현해야 한다. 삽입 뒤 키 변경은 조회·무효화를 깨뜨릴 수 있다. | [HashMap의 캐시 키 예제](src/main/java/mapstorage/hashmap/README.md#9-캐시와-트랜잭션으로-가져갈-네-가지) |
| 해시 분산과 충돌 | 키 계약이 맞아도 편중된 hash는 조회 비용을 늘린다. 충돌 처리와 캐시 퇴출 정책은 다른 문제다. | [캐시의 책임](src/main/java/mapstorage/cache/README.md) |
| 메모리에 키·값 저장 | 원본에서 재생성할 캐시와, 커밋된 상태를 복구해야 하는 저장소는 유실에 대한 요구가 다르다. | [캐시에서 저장소로](src/main/java/mapstorage/storage/README.md) |
| put으로 한 키의 값 교체 | 두 put이 하나의 성공·실패 단위가 되지는 않는다. HashMap 자체에는 스레드 안전성도 없다. | [동시성](src/main/java/mapstorage/concurrency/README.md) → [원자적 이체](src/main/java/mapstorage/storage/atomic/README.md) |

HashMap에서는 키 계약, 조회/교체, 충돌, 확장의 의미를 설명할 수 있으면 다음 단계로 간다.
비트 연산과 트리 내부는 성능을 더 이해하기 위한 심화다. remove의 모든 분기나
레드-블랙 트리 회전을 끝까지 외우는 것은 캐시·트랜잭션 학습의 선행 조건이 아니다.
TreeMap도 선택 심화이며 HashMap 버킷의 TreeNode와 별도 구현이다.

현재 패키지에 RDB 트랜잭션 실행 실험은 없다. 마지막 문서의
[RDB로 이어지는 비교와 실습 질문](src/main/java/mapstorage/storage/README.md#rdb-트랜잭션과-비교하기)을
다음 학습의 출발점으로 삼는다.

패키지는 `src/main/java/mapstorage/` 아래에 있고 테스트는 동일 경로의
`src/test/java/`에 있다. 뒤의 패키지는 해당 학습 커밋에서 추가된다.

```bash
./gradlew :topics:map-storage:test
./gradlew :topics:map-storage:test --tests 'mapstorage.hashmap.*'
```

IDE에서 각 Demo의 main을 실행하거나 아래 명령을 사용하고, 패키지 README의 중단점에 들어가 호출을 따라간다.

```bash
./gradlew :topics:map-storage:runDemo -Pdemo=mapstorage.hashmap.HashMapDemo
./gradlew :topics:map-storage:runDemo -Pdemo=mapstorage.concurrency.CounterDemo
./gradlew :topics:map-storage:runDemo -Pdemo=mapstorage.cache.lru.LruDemo
./gradlew :topics:map-storage:runDemo -Pdemo=mapstorage.cache.caffeine.CaffeineDemo
./gradlew :topics:map-storage:runDemo -Pdemo=mapstorage.storage.atomic.AtomicDemo
./gradlew :topics:map-storage:runDemo -Pdemo=mapstorage.storage.recovery.RecoveryDemo -Pwal=/tmp/map-balances.wal
```

runDemo 작업은 전체 과정을 정리하는 마지막 소주제 커밋에서 추가된다.
이전 커밋에서는 IDE의 main 실행과 각 패키지의 테스트 명령을 사용한다.
문서의 JDK 내부 설명은 OpenJDK `jdk-21+35` 기준이다. 설치된 JDK 패치 버전에
따라 줄 번호가 달라질 수 있어 메서드와 분기 조건으로 위치를 찾는다.
내부 구조 검증에 필요한 `--add-opens`는 HashMap 테스트 작업에 설정한다.
벤치마크 수치를 근거로 삼는 과정이 아니므로 실행 시간으로 성능 결론을 내리지 않는다.

커밋 날짜는 학습용으로 구성한 일정이며 실제 작업 시각을 뜻하지 않는다.
2026-07-30부터 1~3일 간격으로 진행하고 평일 시각은 Asia/Seoul 20시 이후로 맞춘다.

## 패키지별 읽을 문서와 학습 일정

| 날짜 (KST) | 문서 | 확인할 결과 |
| --- | --- | --- |
| 07-30 목 21:17:42 | [HashMap](src/main/java/mapstorage/hashmap/README.md) | 충돌·교체·가변 키·리사이즈·트리화 |
| 08-01 토 20:34:15 | [동시성](src/main/java/mapstorage/concurrency/README.md) | 갱신 유실·compute·두 키의 중간 상태 |
| 08-03 월 21:09:05 | [LRU](src/main/java/mapstorage/cache/lru/README.md) | 읽기로 바뀌는 순서와 모니터 |
| 08-04 화 21:18:32 | [Caffeine](src/main/java/mapstorage/cache/caffeine/README.md) | 데이터 게시·정책 정리·만료 |
| 08-07 금 21:17:25 | [원자적 이체](src/main/java/mapstorage/storage/atomic/README.md) | 롤백과 독자 차단 |
| 08-10 월 21:39:51 | [로그 복구](src/main/java/mapstorage/storage/recovery/README.md) | 커밋 전후 강제 종료와 redo |

간격 2, 2, 1, 3, 3일은 seed=7302026의 난수 일정으로 구성했다.
기존 커밋에는 이 일정보다 늦은 날짜도 있으므로 날짜 정렬 대신 부모 연결 순서로 따라간다.
기존 커밋의 시각이나 내용은 변경하지 않는다.

```bash
git log --reverse --format='%h %aI %s' -- topics/map-storage
```

별도의 작업 디렉터리에서 해당 해시를 확인하려면 아래와 같이 실행한다.
현재 작업 디렉터리의 미커밋 변경을 옮길 필요가 없다.

```bash
git worktree add --detach /tmp/map-lesson <위에서-고른-커밋-해시>
cd /tmp/map-lesson
./gradlew :topics:map-storage:test
```

각 패키지에서 결과를 먼저 예측하고, 테스트를 실행하고, 실제 라이브러리 메서드로
Step Into한 뒤 문서의 다음 질문에 답한다. 구현 관찰과 공개 API 보장 범위를 구분한다.
