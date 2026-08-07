# 5. 두 키 변경을 묶고 실패하면 되돌리기

## 실행

`AtomicDemo.main`은 실패 후 A=1000/B=1000, 성공 후 A=900/B=1100을 출력한다.
Map의 출력 순서는 보장하지 않는다.
`./gradlew :topics:map-storage:test --tests 'mapstorage.storage.atomic.*'`로
롤백, 검증 실패, 오버플로, 읽기 차단을 확인한다.

## 우리 호출부를 먼저 따라가기

1. public transfer → package-private synchronized transfer로 진입한다.
   이때 잠기는 대상은 AtomicBalances 인스턴스다. 내부 HashMap 자체의 모니터가 아니다.
2. 같은 계좌·없는 계좌·0 이하 금액·잔액 부족을 쓰기 전에 거부한다.
   받는 계좌의 덧셈은 Math.addExact로 long 오버플로를 쓰기 전에 탐지한다.
3. oldFrom과 oldTo를 지역 변수로 보관한다. 이것이 두 키만을 위한 작은 undo 정보다.
4. HashMap.put으로 출금 계좌를 바꾸고 테스트 전용 afterDebit을 호출한다.
   여기서 예외를 발생시키면 catch가 두 값을 복구하고 예외를 다시 던진다.
5. 성공하면 입금 계좌도 put한다. 모니터를 놓은 후 다른 스레드의 snapshot이 진입한다.
   내부 HashMap.put 경로는 앞의 [hashmap 문서](../../hashmap/README.md)와 같다.

[Math.addExact 구현](https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/lang/Math.java)은
합과 피연산자의 부호 관계를 검사해 오버플로에서 ArithmeticException을 던진다.
Map.copyOf는 불변 결과를 만드는 API다. snapshot에서는 **복사하는 동안에도 같은 잠금**을
유지하므로 두 키가 같은 이체 경계에서 관찰된다. 불변 복사 자체가 동시성 트랜잭션은 아니다.

## 읽는 쪽이 같은 잠금에 참여해야 한다

snapshot → 인스턴스 모니터 획득 → Map.copyOf(balances) → 모니터 해제.
맵을 직접 돌려주거나 잠금 없는 get을 추가하면 이 보장이 깨진다.
모니터 해제와 이후 같은 모니터 획득 사이의 happens-before가 변경의 가시성도 제공한다.
HashMap을 ConcurrentHashMap으로 바꾸는 것만으로는 이 읽기 경계를 만들 수 없다.

readerBlocksDuringIntermediateState는 출금 직후 작성 스레드를 latch로 멈추고
snapshot 호출 스레드가 BLOCKED 상태인지 확인한다. 일정 시간 ‘잘 될 것’이라고 기다리는
실험이 아니라 실제 모니터 진입 차단을 관찰한다. latch를 풀고 나면 독자는 완료된 상태만 얻는다.
실패 실험은 같은 위치에서 예외를 던져 원래 두 값과 정확히 일치하는지 검사한다.

## 보장 범위와 다음 단계

이 구현의 롤백은 JVM이 계속 실행되며 복구용 put을 수행할 수 있는 상황을 다룬다.
심각한 메모리 고갈이나 프로세스 강제 종료 때 catch가 반드시 완료되는 것은 아니다.
테스트 전용 콜백은 외부 API로 노출하지 않는다. 모니터는 재진입 가능하므로 같은 스레드가
콜백 안에서 snapshot을 호출하면 중간 상태를 볼 수 있다. 일반 transfer는 그런 콜백을 받지 않는다.
파일도 없으므로 재시작하면 데이터가 사라진다. 다음 소주제에서는 변경을 먼저 파일에 남긴다.
