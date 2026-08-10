# 6. 커밋 로그로 프로세스 종료 이후 복구하기

## 실행

`RecoveryDemo.main`에 WAL 파일 경로를 인자로 전달한다. 예: `/tmp/map-balances.wal`.
처음에는 A=1000/B=1000에서 시작하고, 이체와 다시 열기 이후에는 A=900/B=1100이다.
같은 파일로 다시 실행하면 이전 상태부터 100씩 이체하므로 무한 반복할 수는 없다.

```bash
./gradlew :topics:map-storage:test --tests 'mapstorage.storage.recovery.*'
```

테스트는 임시 디렉터리를 사용한다. CrashWriter는 테스트 전용 별도 JVM에서만 실행한다.
Runtime.halt(23)으로 close와 종료 훅을 건너뛴다. 주 테스트 JVM은 종료하지 않는다.

## 이번 저장소의 범위

A/B 두 계좌, 초기값 각각 1000, 하나의 WAL 파일, 한 번에 하나의 작성자다.
Map은 메모리 조회용이고 WAL이 복구의 기준이다. 별도의 데이터 파일은 만들지 않는다.
복구 때 커밋된 after-image를 다시 적용하는 redo 방식이다.
일반 키 직렬화·체크포인트·로그 압축·다중 프로세스 동시 읽기·분산 트랜잭션은 구현하지 않는다.
이 제한 덕분에 ‘커밋을 어떤 바이트로 구분하고 언제 메모리를 바꾸는가’를 끝까지 읽을 수 있다.

## 파일 형식

모든 정수는 ByteBuffer 기본값인 big endian이다. 한 프레임은 36바이트다.

| 바이트 오프셋 | 길이 | 필드 |
| --- | --- | --- |
| 0 | 4 | magic: MAP1 |
| 4 | 4 | kind: BEGIN=1, COMMIT=2 |
| 8 | 8 | tx: 순차 트랜잭션 번호 |
| 16 | 8 | a: 변경 후 A 잔액 |
| 24 | 8 | b: 변경 후 B 잔액 |
| 32 | 4 | 앞 32바이트의 CRC32 |

BEGIN은 단순한 시작 표시를 넘어 변경 후 두 값을 담는다. COMMIT도 같은 tx/a/b를 담아
짝을 검증한다. 한 트랜잭션은 72바이트다. CRC32는 우발적 손상 탐지용으로, 인증이나
임의의 손상을 완벽히 검출하는 보장이 아니다. 계좌 값과 합계 2000도 복구 시 검증한다.

## transfer 호출을 디스크까지 따라가기

1. public transfer → synchronized transfer → ensureUsable → 입력 검증.
   새 값은 임시 맵에서 계산한다. 아직 조회용 balances에는 변경을 적용하지 않는다.
2. append(BEGIN)에서 ByteBuffer에 필드를 넣고 CRC32를 계산한다. flip으로 읽기 위치를
   되돌린 뒤 hasRemaining 동안 FileChannel.write를 반복한다. 한 번의 write가
   전체 버퍼를 쓴다는 보장이 없어서 반복이 필요하다.
3. log.force(true) 뒤 BEFORE_COMMIT 중단점. 여기서 죽으면 변경 후 값은 파일에 있어도
   커밋이 없으므로 복구 때 적용하지 않는다.
4. append(COMMIT) → log.force(true) → AFTER_COMMIT 중단점.
   여기서 죽으면 메모리가 아직 바뀌지 않았어도 재시작 시 커밋된 값이 적용된다.
5. balances.putAll(next), lastTx 갱신 후 반환한다. snapshot도 같은 모니터를 사용하므로
   정상 실행 중 독자가 두 값의 적용 사이에 끼어들지 않는다.

[FileChannelImpl.java](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/sun/nio/ch/FileChannelImpl.java)의
write(ByteBuffer) → IOUtil.write 경로에 중단점을 둔다.
[IOUtil.java](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/sun/nio/ch/IOUtil.java)는
우리 힙 ByteBuffer를 임시 direct buffer로 옮기고 writeFromNativeBuffer → nd.write로 전달한다.
실제 쓴 길이만큼 원본 버퍼의 position을 전진시킨다. 우리 while은 그 position을 기준으로 계속 쓴다.

force(true)는 FileChannelImpl.force → nd.force로 간다. 현재 macOS에서는
[FileDispatcherImpl.java](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/macosx/classes/sun/nio/ch/FileDispatcherImpl.java)가
UnixFileDispatcherImpl의 force를 오버라이드한다.
[macOS 네이티브 force0](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/macosx/native/libnio/ch/FileDispatcherImpl.c)은
fcntl(fd, F_FULLFSYNC)를 호출한다. 실패 뒤 fstatfs가 성공하고 비로컬 파일 시스템으로
판정되는 경우 fsync를 시도한다. 모든 실패에서 무조건 fsync로 대체하는 것은 아니다.
따라서 이 환경에서 무조건 Unix의 공통 force0을 호출한다고 설명하면 틀린다.

비교용 [Unix 공통 네이티브 구현](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/unix/native/libnio/ch/UnixFileDispatcherImpl.c)은
metaData 값에 따라 fsync/fdatasync를 선택한다. JDK에서 호출을 내리는 것과
스토리지 장치가 제공하는 장애 내구성은 서로 다른 경계다. 버퍼에 put하거나 write가 반환된
것만으로 force가 끝났다고 취급하지 않는다.

## recover 호출 경로

생성자 → FileChannel.open(CREATE, READ, WRITE) → tryLock → recover.
tryLock으로 파일 전체의 배타 잠금을 보유하고 close에서 해제한다. 같은 JVM의 중복 잠금은
OverlappingFileLockException, 다른 작성자의 잠금은 null일 수 있어 둘 다 실패로 처리한다.
이 잠금은 같은 잠금 규약을 따르는 작성자에 대한 보호다.

recover는 position(0)부터 프레임을 채울 때까지 read를 반복한다.
완전한 프레임은 magic/CRC/잔액/순서를 검증한다. BEGIN에서 pending만 저장하고,
동일한 tx/a/b를 가진 COMMIT에서만 balances와 lastTx를 갱신한다.
COMMIT 직후의 파일 위치를 committedEnd로 기억한다.

EOF에서 부분 프레임이나 커밋 없는 BEGIN이 남으면 마지막 committedEnd로 truncate하고 force한다.
이 과정을 생략하고 불완전한 꼬리 뒤에 새 트랜잭션을 붙이면 이후 복구가 그 꼬리에서 막힐 수 있다.
완전한 프레임의 CRC가 틀리면 IOException으로 열기를 중단하고 파일은 보존한다.
완전한 손상 프레임을 무조건 무시하면 이미 커밋된 데이터를 조용히 잃을 수 있기 때문이다.

## 검증이 보여 주는 것

- 커밋 전 강제 종료: BEGIN만 남고 A/B는 초기값으로 복구된다. 이후 이체와 재복구도 가능하다.
- 커밋 후 강제 종료: 메모리 반영 전 종료해도 A=900/B=1100으로 복구된다.
- 두 번째 트랜잭션의 1~71바이트만 남기는 모든 절단 위치: 첫 커밋은 유지하고 꼬리는 제거한다.
  각 경우 새 이체를 추가하고 재복구까지 검사한다.
- 완전한 프레임의 바이트 손상: 열기가 실패하며 원본 바이트를 변경하지 않는다.
- 재열기를 반복해도 금액이 중복 차감되지 않는다. 로그는 연산 재실행 대신 절대값을 적용한다.

## 예외 반환이 미커밋을 뜻하지는 않는다

COMMIT을 기록했는데 이후 오류가 나거나 응답 전에 죽으면 호출자는 결과를 확정할 수 없다.
transfer의 I/O 또는 주입 오류 뒤에는 인스턴스를 failed로 표시하고 snapshot/추가 이체를 막는다.
반드시 닫고 다시 열어 로그에서 결과를 판정한다. 테스트는 AFTER_COMMIT에서 예외를 던지고
재열기 후 변경이 살아 있음을 확인한다. 실제 서비스의 중복 요청 방지에는 요청 식별자와
멱등 처리까지 필요하며 이 구현의 내부 tx 번호만으로 해결되지 않는다.

이 테스트는 **프로세스 종료** 복구를 검증한다. OS 전원 차단·디스크 고장 실험은 아니다.
새 파일의 디렉터리 엔트리 동기화, 체크포인트, 매체 손상 복구 등까지 구현한 DB는 아니다.
Caffeine의 메모리 정책 큐와 이 WAL의 차이는 큐라는 모양이 아니라 기록 위치·force·커밋 판정 규칙이다.
