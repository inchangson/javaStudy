# Semaphore에서 연결 풀까지

`docs/topic-ideas.md` 1번: **연결을 더 늘리면 왜 오히려 느려질까?**

Java 21로 실행하며 외부 라이브러리는 각 단계에서 버전을 고정한다. 각 패키지의 README는 예제 호출부에서 라이브러리 내부 획득·반환·실패 경로까지 이어진다. 코드와 문서를 함께 읽고 해당 커밋의 테스트를 실행한다.

## 학습 순서 / 커밋 시간

커밋 날짜는 실제 학습 이력의 증명이 아니라 요청에 맞춰 재구성한 학습 일정이다. 2026년 8월 11일 이후, 고정 seed `8112026`의 1~3일 간격으로 생성했다. 시간대는 Asia/Seoul이며 평일은 20시 이후다.

| 단계 | 일정 (+09:00) | 내용 |
|---|---|---|
| 01 | 08-12 21:48:23 | [Semaphore](src/main/java/poolstudy/semaphore/README.md): 허가 수와 소유권 |
| 02 | 08-14 21:59:41 | [작은 객체 풀](src/main/java/poolstudy/tiny/README.md): lease, 시간 제한, 종료 |
| 03 | 08-16 21:07:49 | [Commons Pool](src/main/java/poolstudy/commons/README.md): 생성·검증·반환·폐기 |
| 04 | 08-17 21:18:23 | [HikariCP](src/main/java/poolstudy/hikari/README.md): ConcurrentBag과 JDBC proxy |
| 05 | 08-20 21:52:38 | Lettuce: Commons 기반 동기 풀과 비동기 풀 |
| 06 | 08-23 21:03:40 | 크기 실험: 획득 대기와 자원 경합 분리 |

```bash
./gradlew :topics:connection-pool:test
./gradlew :topics:connection-pool:runDemo -Pdemo=poolstudy.semaphore.SemaphoreDemo
```
