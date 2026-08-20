# 05. Lettuce 6.5.5.RELEASE: 동기 풀과 비동기 풀은 같은 대기를 하지 않는다

## 실행

```bash
# 서버 없이 실제 BoundedAsyncPool 알고리즘 검증
./gradlew :topics:connection-pool:test --tests 'poolstudy.lettuce.BoundedAsyncPoolTest'
# Docker의 임시 Redis 7.4.2, 임의의 localhost 포트, 종료 시 해당 컨테이너만 제거
./topics/connection-pool/scripts/verify-redis.sh
# 직접 준비한 Redis도 가능
REDIS_URI=redis://127.0.0.1:6379/0 ./gradlew :topics:connection-pool:redisTest
```

단위 테스트는 fake async **factory**와 실제 Lettuce **pool 구현**을 사용한다. 네트워크를 검증하는 테스트는 redisTest로 분리하고 REDIS_URI가 없으면 실패하도록 했다. 일반 test의 성공을 Redis 통합 성공으로 해석하지 않는다. 실제 Redis 데모의 기대 출력은 `Observation[multiLeaked=true, ping=PONG, asyncPing=PONG]`이다. 키 데이터 변경 없이 MULTI/DISCARD/PING만 사용한다.

## 동기 어댑터: Commons Pool 위의 return-on-close

Maven `io.lettuce:lettuce-core:6.5.5.RELEASE:sources`를 읽었다. 고정 소스: [ConnectionPoolSupport](https://github.com/redis/lettuce/blob/6.5.5.RELEASE/src/main/java/io/lettuce/core/support/ConnectionPoolSupport.java), [ConnectionWrapping](https://github.com/redis/lettuce/blob/6.5.5.RELEASE/src/main/java/io/lettuce/core/support/ConnectionWrapping.java).

1. `LettuceDemo.observe → ConnectionPoolSupport.createGenericObjectPool(client::connect, config)`는 wrapConnections=true 오버로드를 부른다. 내부에서 `RedisPooledObjectFactory`를 넣은 GenericObjectPool의 익명 하위 클래스를 만든다.
2. 인자 없는 `borrowObject()`를 override하여 `super.borrowObject()`가 빌린 **원본** 연결을 `ConnectionWrapping.wrapConnection`으로 감싼다. 큐·생성 상한·대기·timeout은 [Commons 단계](../commons/README.md)의 구현을 그대로 사용한다. adapter가 override한 메서드가 무엇인지 확인하고 예제와 같은 인자 없는 borrow를 사용한다.
3. 생성은 factory.create → supplier.get → RedisClient.connect다. validate는 **isOpen() 검사**이며 Redis에 PING해 왕복 확인하는 동작이 아니다. `testOnBorrow=true`를 켰다고 세션이 깨끗하거나 다음 명령 성공이 보장되지 않는다.
4. `try(first)`의 종료는 Java dynamic proxy의 `ReturnObjectOnCloseInvocationHandler → pool.returnObject(proxiedConnection)`로 들어간다. 풀의 override가 HasTargetConnection에서 원본을 꺼낸 뒤 Commons에 반환한다. handler는 내부 연결 참조를 지워 반환한 wrapper의 재사용을 막는다. 이 버전에서 같은 wrapper를 다시 close하면 deallocated 예외가 날 수 있어 Hikari의 idempotent close와 같다고 가정하지 않는다.
5. pool.close 시 Commons가 factory.destroyObject를 호출하면 **원본** StatefulConnection.close가 호출되어 물리 연결을 종료한다. wrapConnections=false라면 호출자의 connection.close도 물리 close이므로 반드시 pool.returnObject(connection)를 사용해야 한다.

## 왜 MULTI가 다음 대여자에게 남나?

이 RedisPooledObjectFactory에는 activate/passivate override가 없다. BasePooledObjectFactory의 기본 no-op이 사용된다. 따라서 Hikari의 rollback/reset과 달리 자동 DISCARD가 없다.

예제의 `first.sync().multi()` 후 wrapper만 닫으면, 다음 대여자의 `second.isMulti()`가 true다. second가 명시적으로 DISCARD한 뒤 PING해야 정상적인 PONG을 받는다. 실제 Redis 통합 테스트가 이 사실을 검증한다. 이것은 풀 재사용 문제를 드러내기 위한 의도적 상태 누출이며, 정상 코드는 첫 대여 범위에서 EXEC 또는 DISCARD를 완료하고 반환해야 한다. 실패로 상태를 확신할 수 없는 연결은 유효 상태로 복구하거나 폐기하는 정책이 필요하다.

명령 실행도 호출부에서 추적할 수 있다. `StatefulRedisConnectionImpl.sync()`가 제공하는 sync API는 `FutureSyncInvocationHandler`를 통해 async 메서드를 호출하고 완료를 기다린다. `AbstractRedisAsyncCommands.ping/multi → dispatch → StatefulRedisConnectionImpl.dispatch → RedisChannelHandler.dispatch → RedisChannelWriter.write`로 이어진다. 일반 연결에서는 DefaultEndpoint가 Netty channel에 command를 쓰고 응답 처리가 future를 완료한다. MULTI 관련 상태는 StatefulRedisConnectionImpl의 명령 전처리/응답 callback과 연결된다. **pool의 대여 대기**와 **명령 응답 대기**는 별개다.

관련 소스: [StatefulRedisConnectionImpl](https://github.com/redis/lettuce/blob/6.5.5.RELEASE/src/main/java/io/lettuce/core/StatefulRedisConnectionImpl.java), [FutureSyncInvocationHandler](https://github.com/redis/lettuce/blob/6.5.5.RELEASE/src/main/java/io/lettuce/core/FutureSyncInvocationHandler.java), [AbstractRedisAsyncCommands](https://github.com/redis/lettuce/blob/6.5.5.RELEASE/src/main/java/io/lettuce/core/AbstractRedisAsyncCommands.java), [RedisChannelHandler](https://github.com/redis/lettuce/blob/6.5.5.RELEASE/src/main/java/io/lettuce/core/RedisChannelHandler.java), [DefaultEndpoint](https://github.com/redis/lettuce/blob/6.5.5.RELEASE/src/main/java/io/lettuce/core/protocol/DefaultEndpoint.java).

## 비동기 어댑터: future를 반환하지만 대기자를 쌓아두지는 않는다

[AsyncConnectionPoolSupport](https://github.com/redis/lettuce/blob/6.5.5.RELEASE/src/main/java/io/lettuce/core/support/AsyncConnectionPoolSupport.java), [BoundedAsyncPool](https://github.com/redis/lettuce/blob/6.5.5.RELEASE/src/main/java/io/lettuce/core/support/BoundedAsyncPool.java)를 연다.

`createBoundedObjectPoolAsync(() -> client.connectAsync(...), config, false)`는 BoundedAsyncPool을 만들고 필요한 초기화를 future로 돌려준다. false이므로 데모는 원본 연결을 받고 AsyncLease가 release 책임을 가진다. Netty 이벤트 루프에서 `.join()`하는 예제가 아니다. main의 최종 결과·종료 지점에서만 기다린다.

1. `AsyncLease.use → pool.acquire → BoundedAsyncPool.acquire`는 cache 큐에서 idle 객체를 poll한다. 있으면 설정에 따라 async factory.validate 뒤 완료한다.
2. 없으면 objectCount + objectsInCreationCount를 maxTotal과 비교한다. `makeObject0`에서 생성 예약 카운터를 증가시키고 상한을 다시 확인하여 동시 생성도 용량을 넘지 않게 한다. 생성 실패 시 예약 수를 줄이고 실패 future로 반환한다.
3. 여지가 없으면 **POOL_EXHAUSTED(NoSuchElementException)로 즉시 실패한 future**다. 반환될 때까지 기다리는 pending borrower queue가 없다. '비동기'는 성공할 때까지 기다려준다는 의미가 아니다. 무제한 재시도 대신 호출자 측 동시성 제한·유한 큐·거절 정책을 따로 설계해야 한다.
4. `release → all.contains`로 풀 소속을 확인하고 maxIdle 초과 또는 release 검증 실패면 destroy, 아니면 `return0 → idleCount 증가 → cache.add`로 돌린다. Commons의 PooledObject ALLOCATED/RETURNING 검사와 동일하지 않다. 원본 연결의 중복 release 방지는 호출자 책임으로 취급한다.
5. factory의 create는 connectAsync, destroy는 connection.closeAsync, validate는 isOpen 완료 future다. 비동기에도 JDBC 같은 rollback/passivate가 자동 추가되지 않는다.

## 작업 완료, 반환 완료, 취소는 서로 다르다

`AsyncLease.use`는 action이 동기 throw하거나 future가 실패해도 반환하고, **release 완료 뒤** 결과 future를 끝낸다. 작업·반환이 둘 다 실패하면 작업 실패에 반환 실패를 suppressed로 남긴다. acquire 자체가 실패했다면 반환할 객체가 없으므로 action과 release를 실행하지 않는다. 각 예외 분기의 단위 테스트와 실제 async PING을 함께 읽는다.

helper는 acquire callback을 호출자 result future와 독립적으로 등록한다. 호출자가 먼저 취소한 상태에서 연결이 늦게 생기면 action을 시작하지 않고 반환한다. acquire.thenCompose로만 연결하면 파생 future 취소가 callback 실행을 막아 자원이 남을 수 있어 이 경로를 별도 테스트한다. 이미 action이 시작된 뒤 취소하면 작업 완료까지 기다렸다가 반환한다. 취소된 result에는 이후 반환 오류를 전달할 수 없다는 한계도 있다.

이 helper는 일반적인 취소/전체 deadline 프레임워크가 아니다. 바깥 future를 cancel해도 Redis 명령 취소가 전파된다고 보장하지 않는다. 작업이 끝나지 않으면 연결도 계속 대여 상태다. 명령을 보냈는데 timeout만 보고 먼저 반환하면 다음 대여자의 명령과 이전 작업이 섞일 수 있다.

별도 테스트는 **raw BoundedAsyncPool acquire future를 생성 완료 전에 취소**한다. 이 버전 `completeAcquire`는 이미 취소된 future를 보면 늦게 생성한 객체를 return0으로 되돌린다. 이 관찰을 wrapper의 thenApply로 파생된 future, 모든 cancel/complete 경쟁 순서, 실제 Redis 명령 취소까지 확장하지 않는다.

종료도 별도 경계다. 이 버전 `closeAsync → clearAsync`는 cache에 있는 idle 객체를 비운다. 모든 대여 중 작업과 생성 중 작업이 끝날 때까지 drain하는 API가 아니다. 데모는 명령과 release를 완료하고 pool.closeAsync를 기다린 다음 client.shutdown한다. 종료 후 늦은 반환까지 알아서 물리 종료될 것이라고 가정하지 않는다.

## 연결 수와 요청 수를 같은 숫자로 보면 안 되는 이유

Lettuce 연결은 여러 일반 명령을 공유할 수 있다. 반면 blocking 명령이나 MULTI/EXEC 같은 연결 상태는 전용 연결을 고려해야 한다. shared connection 하나에도 여러 in-flight 명령이 생길 수 있으므로 maxTotal=8이 항상 동시 명령 8개를 뜻하지 않는다. Hikari의 일반 JDBC 대여와 수치를 그대로 비교하면 안 된다. [공식 pooling 가이드](https://redis.github.io/lettuce/advanced-usage/connection-pooling/)도 공유 연결과 풀 사용 목적을 구분한다. 최종 [비교·크기 실험](../sizing/README.md)에서 대기 위치를 한 표로 정리한다.
