# Java 학습 주제 아이디어

작성일: 2026-09-12

이력서와 연결된 블로그 경력 회고를 바탕으로 정리한 학습 후보다. 실제 업무에서 아래 기술을 모두 사용했다는 뜻은 아니다.

Java 구현을 직접 읽고 실험한 뒤, 그 구현이 해결하는 문제를 데이터베이스·네트워크·운영 설계로 확장하는 방향이다. 화살표는 학습 순서이며, 기술 간 상속 관계나 동일한 보장을 뜻하지 않는다. ★는 우선 추천 후보다. 확정된 진행 순서는 아니다.

## 기준이 된 주제

**Java Map은 어디까지 저장소가 될 수 있을까?**

`HashMap → ConcurrentHashMap → LinkedHashMap으로 만든 LRU → Caffeine`

조회·삽입·충돌·리사이즈에서 시작해 원자성의 범위, 캐시 정책 관리, 경합과 메모리 가시성을 탐구한다. 이후 두 키 갱신과 파일 복구를 직접 구현하며 트랜잭션과 영속성으로 확장할 수 있다. Caffeine 자체가 트랜잭션 저장소이거나 장애 복구용 WAL을 제공한다는 의미는 아니다.

## 추가 후보 45개

| # | 경험 연결 | Java에서 출발하는 탐구 경로 | 확장되는 질문 |
|---|---|---|---|
| 1 ★ | 외부 API 연결 풀 | `Semaphore` → 작은 객체 풀 → HikariCP | 연결을 더 늘리면 왜 오히려 느려질까? |
| 2 ★ | 외부 API 병렬 호출 | `BlockingQueue` → `ThreadPoolExecutor` → 거절 정책 | 처리 속도보다 요청이 빠르면 어디에 쌓일까? |
| 3 ★ | 타임아웃 처리 | `interrupt` → `Future.cancel` → `CompletableFuture` | 기다리기를 끝내면 실제 작업도 멈출까? |
| 4 | 파트너 API 장애 격리 | 공유 Executor → 전용 Executor → `Semaphore` 격리 | 느린 파트너 하나가 전체를 막는 이유는? |
| 5 | 외부 API 재시도 | 반복문 → 지연 스케줄링 → retry 구현 비교 | 재시도가 부하를 증폭시키는 조건은? |
| 6 | 실패 후 cooldown | `enum` 상태 → CAS → 작은 Circuit Breaker | 장애 상태를 누가, 언제 전환해야 할까? |
| 7 ★ | Gateway 요청 처리 | `Socket` → `SocketChannel`·`Selector` → Netty | 적은 스레드로 많은 연결을 어떻게 다룰까? |
| 8 | HTTP 연결 재사용 | 스트림 읽기 → 응답 종료 → HTTP 클라이언트 풀 | 응답을 덜 읽으면 연결은 어떻게 될까? |
| 9 | 가상 스레드·외부 호출 | 플랫폼 스레드 → 가상 스레드 → 동시 호출 제한 | 스레드가 싸져도 제한해야 하는 자원은? |
| 10 | 호출 결과·로그 | `ThreadLocal` → MDC → 비동기 문맥 전달 | 스레드가 바뀌면 요청 정보는 어디로 갈까? |
| 11 ★ | 동적 인증·Gateway 설정 | 가변 객체 → 불변 객체 → `AtomicReference` 교체 | 요청 하나가 서로 다른 버전의 설정을 읽을까? |
| 12 | 설정 공유 | `final` → `volatile` → 안전한 객체 공개 | 객체를 만들었다는 것과 보인다는 것은 같을까? |
| 13 | 설정 변경 감지 | `Files` 메타데이터 → `WatchService` → 재스캔 | 변경 알림만 믿어도 최신 상태를 알 수 있을까? |
| 14 ★ | 설정 파일 저장 | `OutputStream` → `FileChannel.force` → 파일 교체 | 쓰기 완료와 장애 후 보존은 어떻게 다를까? |
| 15 | 설정 버전 판별 | 수정 시각 → `MessageDigest` → 버전 번호 | 파일의 동일성과 최신성을 어떻게 판단할까? |
| 16 ★ | Config Server 확장 | 위임 객체 → JDK Proxy → `BeanPostProcessor` | 인터페이스가 같으면 원본을 완전히 대체할까? |
| 17 | 설정 갱신·Bus | Observer → Spring 이벤트 → 메시지 전달 | 메서드 호출이 이벤트가 되면 무엇이 달라질까? |
| 18 | 동적 라우트·리스너 | 동기화 List → `CopyOnWriteArrayList` → 스냅샷 | 순회 도중 설정이 바뀌면 무엇을 보여줄까? |
| 19 ★ | OTP·인가 코드 단일 사용 | `AtomicReference` → CAS 상태 전이 → 버전 검사 | “한 번만 성공”을 어느 범위까지 보장할까? |
| 20 | OTP 만료 | `Clock` → `DelayQueue` → 만료 스케줄러 | 시간이 지났다는 판단과 삭제 시점은 같을까? |
| 21 | OTP 발송 제한 | `AtomicLong` → 고정 구간 제한 → 토큰 버킷 | 횟수 제한과 동시 실행 제한은 어떻게 다를까? |
| 22 | 회원가입·계정 복구 | boolean 조합 → `enum` → sealed 상태 모델 | 불가능한 상태를 타입으로 막을 수 있을까? |
| 23 | OAuth 인증원 확장 | 조건문 → Strategy → `ServiceLoader` | 구현 교체가 호출자에게 새어나오는 지점은? |
| 24 | 인증 Step 구성 | 인터페이스 체인 → Filter → Security 필터 체인 | 순서·중단·예외 처리 책임은 어디에 둘까? |
| 25 | OTP·토큰 생성 | `Random` → `SecureRandom` → 생성기 공급자 | 무작위성과 예측 불가능성은 어떻게 다를까? |
| 26 | 토큰·서명 처리 | 바이트 인코딩 → `MessageDigest`·`Mac`·`Signature` | 해시·위변조 검증·서명은 무엇을 보장할까? |
| 27 | DB TLS 인증서 교체 | `KeyStore` → `TrustManager` → `SSLContext` | 인증서 교체와 기존 연결 수명은 어떻게 얽힐까? |
| 28 ★ | 엑셀 대량 업로드 | 전체 적재 → Iterator → SAX·스트리밍 파싱 | 같은 파일인데 메모리 사용량이 왜 달라질까? |
| 29 | 업로드 입력 검증 | DTO → 값 객체 → 검증 결과 누적 | 한 건 실패와 전체 실패를 어떻게 표현할까? |
| 30 | 외부 등록 배치 | 반복문 → `BlockingQueue` → 생산자·소비자 | 읽기와 외부 전송의 속도 차이를 어떻게 흡수할까? |
| 31 | 정기 작업 | `Timer` → `ScheduledThreadPoolExecutor` | 작업이 주기보다 오래 걸리거나 예외를 던지면? |
| 32 | 배치 중복 실행 | `synchronized` → `ReentrantLock` → `FileLock` | 스레드·프로세스·서버마다 잠금 범위가 왜 다를까? |
| 33 | 병렬 무결성 스캔 | `Future` → `CompletionService` → 결과 집계 | 느린 규칙 하나와 실패 하나를 어떻게 격리할까? |
| 34 | 배치 재시작 | Java 파일 기록 → 체크포인트 → 재실행 | 어디까지 완료했다고 기록해야 안전할까? |
| 35 ★ | Spring 트랜잭션 | JDBC 수동 commit → 직접 프록시 → `@Transactional` | 어노테이션 뒤에서 누가 연결과 commit을 관리할까? |
| 36 | 실패 이력 보존 | JDBC 연결 → savepoint → `REQUIRES_NEW` 비교 | 부분 롤백과 독립 트랜잭션은 어떻게 다를까? |
| 37 | 예외 처리 | `Throwable` → 예외 번역 → suppressed exception | 실패를 변환하면서 어떤 정보를 잃을까? |
| 38 | 연결·파일 자원 정리 | `finally` → `AutoCloseable` → 자원 소유권 | 누가 자원을 닫아야 누수와 조기 종료를 막을까? |
| 39 | 쿠폰 통계 집계 | 반복문 → Stream Collector → 병렬 reduction | 집계가 병렬로 바뀌어도 같은 답을 내려면? |
| 40 | 쿠폰 조회·복합 인덱스 | 정렬 배열 → 이진 탐색 → 작은 B+tree | 동등 검색과 범위 검색에 필요한 구조는? |
| 41 | TV 이벤트 페이지 조회 | `Comparator` → 복합 정렬 키 → 커서 구현 | 같은 시각의 데이터가 있으면 페이지가 안정적일까? |
| 42 | UUID 식별자 | `UUID` → 바이트 표현 → 정렬·직렬화 비교 | 같은 식별자도 표현에 따라 비용이 얼마나 달라질까? |
| 43 ★ | Gateway·API 성능 검증 | `nanoTime` → JMH → JFR | JIT·GC·대기 시간을 성능 개선과 어떻게 구분할까? |
| 44 | 완료 로그·운영 관측 | 동기 파일 로그 → 큐 → 비동기 로깅 | 로그 처리량과 유실 가능성을 어떻게 맞바꿀까? |
| 45 | VM 배포·서비스 종료 | shutdown hook → Executor 종료 → 요청 drain | 종료 신호 뒤 진행 중 작업은 어디까지 보장할까? |

## 활용 방법

주제를 선택하면 `topics/{topic}`의 독립 모듈에서 진행한다. 처음부터 라이브러리 소스 전체를 읽기보다 다음 순서로 질문 하나를 검증한다.

1. 단순한 Java 구현을 만들고 실행 결과를 예상한다.
2. 경합·실패·대량 입력 등 그 구현의 한계를 재현한다.
3. JDK나 라이브러리 소스에서 해당 문제를 해결하는 경로를 읽는다.
4. 결과와 보장 범위, 다른 시스템과의 공통점·차이점을 기록한다.

## 참고 자료

- 경력 회고 원문: [블로그 저장소의 글](https://github.com/inchangson/inchangson.github.io/tree/master/src/content/posts) — 로컬 초안 포함 검토 기준이며 공개 상태는 다를 수 있다.
- [Java 21 동시성 API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/package-summary.html)
- [Spring 트랜잭션 전파](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
- [Caffeine 설계](https://github.com/ben-manes/caffeine/wiki/Design)
