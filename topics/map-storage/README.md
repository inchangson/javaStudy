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

패키지는 `src/main/java/mapstorage/` 아래에 있고 테스트는 동일 경로의
`src/test/java/`에 있다. 뒤의 패키지는 해당 학습 커밋에서 추가된다.

```bash
./gradlew :topics:map-storage:test
./gradlew :topics:map-storage:test --tests 'mapstorage.hashmap.*'
```

IDE에서 각 Demo의 main을 실행하고, README의 중단점에 들어가 호출을 따라간다.
문서의 JDK 내부 설명은 OpenJDK `jdk-21+35` 기준이다. 설치된 JDK 패치 버전에
따라 줄 번호가 달라질 수 있어 메서드와 분기 조건으로 위치를 찾는다.
내부 구조 검증에 필요한 `--add-opens`는 HashMap 테스트 작업에 설정한다.
벤치마크 수치를 근거로 삼는 과정이 아니므로 실행 시간으로 성능 결론을 내리지 않는다.

커밋 날짜는 학습용으로 구성한 일정이며 실제 작업 시각을 뜻하지 않는다.
2026-07-30부터 1~3일 간격으로 진행하고 평일 시각은 Asia/Seoul 20시 이후로 맞춘다.
