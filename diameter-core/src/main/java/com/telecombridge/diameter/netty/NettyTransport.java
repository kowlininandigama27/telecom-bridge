package com.telecombridge.diameter.netty;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.concurrent.ThreadFactory;

/**
 * Picks the best available Netty transport: epoll (Linux), kqueue (macOS/BSD) or NIO.
 * Native transports use nanosecond-precision timers (timerfd / kevent) instead of NIO's
 * millisecond select timeout, which matters for scheduled CCA delays and tail latency.
 * Set {@code -Dtelecombridge.transport=nio} to force NIO.
 */
public enum NettyTransport {
    EPOLL, KQUEUE, NIO;

    public static NettyTransport best() {
        if ("nio".equalsIgnoreCase(System.getProperty("telecombridge.transport"))) {
            return NIO;
        }
        if (Epoll.isAvailable()) {
            return EPOLL;
        }
        if (KQueue.isAvailable()) {
            return KQUEUE;
        }
        return NIO;
    }

    public EventLoopGroup newEventLoopGroup(int threads, ThreadFactory threadFactory) {
        return switch (this) {
            case EPOLL -> new EpollEventLoopGroup(threads, threadFactory);
            case KQUEUE -> new KQueueEventLoopGroup(threads, threadFactory);
            case NIO -> new NioEventLoopGroup(threads, threadFactory);
        };
    }

    public Class<? extends SocketChannel> socketChannel() {
        return switch (this) {
            case EPOLL -> EpollSocketChannel.class;
            case KQUEUE -> KQueueSocketChannel.class;
            case NIO -> NioSocketChannel.class;
        };
    }

    public Class<? extends ServerSocketChannel> serverSocketChannel() {
        return switch (this) {
            case EPOLL -> EpollServerSocketChannel.class;
            case KQUEUE -> KQueueServerSocketChannel.class;
            case NIO -> NioServerSocketChannel.class;
        };
    }
}
