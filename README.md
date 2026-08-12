# Java Study

Java 서적, OOP, 리팩터링, 문법 변화, 동시성 기능을 실행 가능한 예제로 정리하는 학습용 저장소입니다.

## 학습 주제 후보

[Java 학습 주제 아이디어](docs/topic-ideas.md): Map 구현 비교와 경력 경험에서 확장한 45개 학습 후보를 정리합니다.

## 구조

```text
topics/
  oop/
  virtual-thread/
  map-storage/
  connection-pool/
templates/
  topic/
notes/
```

- `topics/{topic}`: 주제별 독립 Gradle 모듈
- `src/main/java`: 콘솔 데모와 실험 코드
- `src/test/java`: 개념 검증용 JUnit 테스트
- `src/jmh/java`: 성능 비교용 JMH 벤치마크
- `templates/topic`: 새 주제를 만들 때 복사하는 기본 템플릿
- `notes`: 코드와 분리해서 보관할 책/강의 메모

Map → 캐시 → 작은 저장소의 구현과 호출 흐름은 [학습 가이드](topics/map-storage/README.md)를 참고합니다.

Semaphore → 객체 풀 → HikariCP·Lettuce 비교는 [연결 풀 학습 가이드](topics/connection-pool/README.md)를 참고합니다.

## 실행

로컬에 Gradle을 설치하지 않아도 Gradle wrapper로 실행합니다.

```bash
./gradlew test
./gradlew :topics:oop:test
./gradlew :topics:virtual-thread:jmh
```

현재 저장소는 Java toolchain을 사용합니다. 기본값은 Java 21이고, 주제 모듈이 필요하면 자체 `build.gradle`에서 버전을 지정합니다.

## 새 주제 추가

```bash
cp -R templates/topic topics/refactoring
```

그 다음 `settings.gradle`에 모듈을 추가합니다.

```groovy
include 'topics:refactoring'
```

주제 모듈의 Java 버전은 해당 모듈의 `build.gradle`에서 정합니다.
