## Spring RSocket/RSocket-java: server keep-alive memory overflow 

### DESCRIPTION

Most recent release of RSocket/RSocket-java [1.1.3](https://github.com/rsocket/rsocket-java/releases/tag/1.1.3) integration 
from spring-boot (most recent - [3.0.0](https://docs.spring.io/spring-framework/docs/current/reference/html/rsocket.html) & 2.7.6) 
is affected by denial-of-service with keepalive-with-payload memory overflow.

Malicious RSocket client may stop reads from socket at arbitrary moment of connection, 
then start sending [KEEP-ALIVE](https://github.com/rsocket/rsocket/blob/master/Protocol.md#keepalive-frame-0x03) frames 
with large payload & RESPOND flag set. Server is required by protocol to reply with KEEP-ALIVE and same data payload, 
RESPOND flag reset. However, because socket is not read by client, KEEP-ALIVE frames are enqueued in server's memory 
indefinitely until overflow (RSocket/RSocket-java lacks check of outbound frames queue size limit).  

Malicious client may send KEEP-ALIVE frames with arbitrarily high frequency, regardless of `keep-alive interval` 
declared in its [SETUP](https://github.com/rsocket/rsocket/blob/master/Protocol.md#setup-frame-0x01) frame 
(RSocket/RSocket-java lacks logic to reject misbehaving clients). 

### PREREQUISITES

jdk 8+

### SETUP

Spring-boot 2.7.6 based application with `RSocket/RSocket-java` service, started with 1GB memory limit: -Xms1024m, -Xmx1024m 
(`springboot-rsocket-service` module).

Malicious RSocket client (small subset of protocol, sufficient for vulnerability demonstration) is implemented 
with Netty as specified in `description` (`keepalive-overflow-client` module).

Malicious client establishes RSocket connection with 100s keep-alive interval. Then sends KEEP-ALIVE frames with 9000 bytes payload in batches 
of 1000 messages every 100 millis, but does not read any data from socket. 

### RUNNING

Build server, client binaries `./gradlew clean build installDist`

Run server `./springboot_rsocket_service.sh` 

Run client `./overflow_client.sh` 

Eventually (several seconds on a modern host, jdk11) `springboot-rsocket-service` reports `OutOfMemoryError`:

```
reactor.netty.ReactorNetty$InternalNettyException: java.lang.OutOfMemoryError: Direct buffer memory
Caused by: java.lang.OutOfMemoryError: Direct buffer memory
        at java.base/java.nio.Bits.reserveMemory(Bits.java:175)
        at java.base/java.nio.DirectByteBuffer.<init>(DirectByteBuffer.java:118)
        at java.base/java.nio.ByteBuffer.allocateDirect(ByteBuffer.java:317)
        at io.netty.buffer.PoolArena$DirectArena.allocateDirect(PoolArena.java:649)
        at io.netty.buffer.PoolArena$DirectArena.newChunk(PoolArena.java:624)
        at io.netty.buffer.PoolArena.allocateNormal(PoolArena.java:203)
        at io.netty.buffer.PoolArena.tcacheAllocateSmall(PoolArena.java:173)
        at io.netty.buffer.PoolArena.allocate(PoolArena.java:134)
        at io.netty.buffer.PoolArena.allocate(PoolArena.java:126)
        at io.netty.buffer.PooledByteBufAllocator.newDirectBuffer(PooledByteBufAllocator.java:396)
        at io.netty.buffer.AbstractByteBufAllocator.directBuffer(AbstractByteBufAllocator.java:188)
        at io.netty.buffer.AbstractByteBufAllocator.directBuffer(AbstractByteBufAllocator.java:179)
        at io.netty.channel.unix.PreferredDirectByteBufAllocator.ioBuffer(PreferredDirectByteBufAllocator.java:53)
        at io.netty.channel.DefaultMaxMessagesRecvByteBufAllocator$MaxMessageHandle.allocate(DefaultMaxMessagesRecvByteBufAllocator.java:120)
        at io.netty.channel.epoll.EpollRecvByteAllocatorHandle.allocate(EpollRecvByteAllocatorHandle.java:75)
        at io.netty.channel.epoll.AbstractEpollStreamChannel$EpollStreamUnsafe.epollInReady(AbstractEpollStreamChannel.java:785)
        at io.netty.channel.epoll.EpollEventLoop.processReady(EpollEventLoop.java:487)
        at io.netty.channel.epoll.EpollEventLoop.run(EpollEventLoop.java:385)
        at io.netty.util.concurrent.SingleThreadEventExecutor$4.run(SingleThreadEventExecutor.java:997)
        at io.netty.util.internal.ThreadExecutorMap$2.run(ThreadExecutorMap.java:74)
        at io.netty.util.concurrent.FastThreadLocalRunnable.run(FastThreadLocalRunnable.java:30)
        at java.base/java.lang.Thread.run(Thread.java:829)
```