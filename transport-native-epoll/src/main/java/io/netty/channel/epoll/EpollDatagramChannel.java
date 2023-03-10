/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.epoll;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultAddressedEnvelope;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramChannelConfig;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.unix.DatagramSocketAddress;
import io.netty.channel.unix.Errors;
import io.netty.channel.unix.IovArray;
import io.netty.channel.unix.Socket;
import io.netty.channel.unix.UnixChannelUtil;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.PortUnreachableException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import static io.netty.channel.epoll.LinuxSocket.newSocketDgram;

/**
 * {@link DatagramChannel} implementation that uses linux EPOLL Edge-Triggered Mode for
 * maximal performance.
 */
public final class EpollDatagramChannel extends AbstractEpollChannel implements DatagramChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(true);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(DatagramPacket.class) + ", " +
            StringUtil.simpleClassName(AddressedEnvelope.class) + '<' +
            StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(InetSocketAddress.class) + ">, " +
            StringUtil.simpleClassName(ByteBuf.class) + ')';

    private final EpollDatagramChannelConfig config;
    private volatile boolean connected;

    /**
     * Create a new instance which selects the {@link InternetProtocolFamily} to use depending
     * on the Operation Systems default which will be chosen.
     */
    public EpollDatagramChannel() {
        this(null);
    }

    /**
     * Create a new instance using the given {@link InternetProtocolFamily}. If {@code null} is used it will depend
     * on the Operation Systems default which will be chosen.
     */
    public EpollDatagramChannel(InternetProtocolFamily family) {
        this(family == null ?
                newSocketDgram(Socket.isIPv6Preferred()) : newSocketDgram(family == InternetProtocolFamily.IPv6),
                false);
    }

    /**
     * Create a new instance which selects the {@link InternetProtocolFamily} to use depending
     * on the Operation Systems default which will be chosen.
     */
    public EpollDatagramChannel(int fd) {
        this(new LinuxSocket(fd), true);
    }

    private EpollDatagramChannel(LinuxSocket fd, boolean active) {
        super(null, fd, active);
        config = new EpollDatagramChannelConfig(this);
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) super.remoteAddress();
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isActive() {
        return socket.isOpen() && (config.getActiveOnOpen() && isRegistered() || active);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public ChannelFuture joinGroup(InetAddress multicastAddress) {
        return joinGroup(multicastAddress, newPromise());
    }

    @Override
    public ChannelFuture joinGroup(InetAddress multicastAddress, ChannelPromise promise) {
        try {
            return joinGroup(
                    multicastAddress,
                    NetworkInterface.getByInetAddress(localAddress().getAddress()), null, promise);
        } catch (IOException e) {
            promise.setFailure(e);
        }
        return promise;
    }

    @Override
    public ChannelFuture joinGroup(
            InetSocketAddress multicastAddress, NetworkInterface networkInterface) {
        return joinGroup(multicastAddress, networkInterface, newPromise());
    }

    @Override
    public ChannelFuture joinGroup(
            InetSocketAddress multicastAddress, NetworkInterface networkInterface,
            ChannelPromise promise) {
        return joinGroup(multicastAddress.getAddress(), networkInterface, null, promise);
    }

    @Override
    public ChannelFuture joinGroup(
            InetAddress multicastAddress, NetworkInterface networkInterface, InetAddress source) {
        return joinGroup(multicastAddress, networkInterface, source, newPromise());
    }

    @Override
    public ChannelFuture joinGroup(
            final InetAddress multicastAddress, final NetworkInterface networkInterface,
            final InetAddress source, final ChannelPromise promise) {

        if (multicastAddress == null) {
            throw new NullPointerException("multicastAddress");
        }

        if (networkInterface == null) {
            throw new NullPointerException("networkInterface");
        }

        try {
            socket.joinGroup(multicastAddress, networkInterface, source);
            promise.setSuccess();
        } catch (IOException e) {
            promise.setFailure(e);
        }
        return promise;
    }

    @Override
    public ChannelFuture leaveGroup(InetAddress multicastAddress) {
        return leaveGroup(multicastAddress, newPromise());
    }

    @Override
    public ChannelFuture leaveGroup(InetAddress multicastAddress, ChannelPromise promise) {
        try {
            return leaveGroup(
                    multicastAddress, NetworkInterface.getByInetAddress(localAddress().getAddress()), null, promise);
        } catch (IOException e) {
            promise.setFailure(e);
        }
        return promise;
    }

    @Override
    public ChannelFuture leaveGroup(
            InetSocketAddress multicastAddress, NetworkInterface networkInterface) {
        return leaveGroup(multicastAddress, networkInterface, newPromise());
    }

    @Override
    public ChannelFuture leaveGroup(
            InetSocketAddress multicastAddress,
            NetworkInterface networkInterface, ChannelPromise promise) {
        return leaveGroup(multicastAddress.getAddress(), networkInterface, null, promise);
    }

    @Override
    public ChannelFuture leaveGroup(
            InetAddress multicastAddress, NetworkInterface networkInterface, InetAddress source) {
        return leaveGroup(multicastAddress, networkInterface, source, newPromise());
    }

    @Override
    public ChannelFuture leaveGroup(
            final InetAddress multicastAddress, final NetworkInterface networkInterface, final InetAddress source,
            final ChannelPromise promise) {
        if (multicastAddress == null) {
            throw new NullPointerException("multicastAddress");
        }
        if (networkInterface == null) {
            throw new NullPointerException("networkInterface");
        }

        try {
            socket.leaveGroup(multicastAddress, networkInterface, source);
            promise.setSuccess();
        } catch (IOException e) {
            promise.setFailure(e);
        }
        return promise;
    }

    @Override
    public ChannelFuture block(
            InetAddress multicastAddress, NetworkInterface networkInterface,
            InetAddress sourceToBlock) {
        return block(multicastAddress, networkInterface, sourceToBlock, newPromise());
    }

    @Override
    public ChannelFuture block(
            final InetAddress multicastAddress, final NetworkInterface networkInterface,
            final InetAddress sourceToBlock, final ChannelPromise promise) {
        if (multicastAddress == null) {
            throw new NullPointerException("multicastAddress");
        }
        if (sourceToBlock == null) {
            throw new NullPointerException("sourceToBlock");
        }

        if (networkInterface == null) {
            throw new NullPointerException("networkInterface");
        }
        promise.setFailure(new UnsupportedOperationException("Multicast not supported"));
        return promise;
    }

    @Override
    public ChannelFuture block(InetAddress multicastAddress, InetAddress sourceToBlock) {
        return block(multicastAddress, sourceToBlock, newPromise());
    }

    @Override
    public ChannelFuture block(
            InetAddress multicastAddress, InetAddress sourceToBlock, ChannelPromise promise) {
        try {
            return block(
                    multicastAddress,
                    NetworkInterface.getByInetAddress(localAddress().getAddress()),
                    sourceToBlock, promise);
        } catch (Throwable e) {
            promise.setFailure(e);
        }
        return promise;
    }

    @Override
    protected AbstractEpollUnsafe newUnsafe() {
        return new EpollDatagramChannelUnsafe();
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        if (localAddress instanceof InetSocketAddress) {
            InetSocketAddress socketAddress = (InetSocketAddress) localAddress;
            if (socketAddress.getAddress().isAnyLocalAddress() &&
                    socketAddress.getAddress() instanceof Inet4Address && Socket.isIPv6Preferred()) {
                localAddress = new InetSocketAddress(LinuxSocket.INET6_ANY, socketAddress.getPort());
            }
        }
        super.doBind(localAddress);
        active = true;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        for (;;) {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                clearFlag(Native.EPOLLOUT);
                break;
            }

            try {
                // Check if sendmmsg(...) is supported which is only the case for GLIBC 2.14+
                if (Native.IS_SUPPORTING_SENDMMSG && in.size() > 1) {
                    NativeDatagramPacketArray array = ((EpollEventLoop) eventLoop()).cleanDatagramPacketArray();
                    in.forEachFlushedMessage(array);
                    int cnt = array.count();

                    if (cnt >= 1) {
                        // Try to use gathering writes via sendmmsg(...) syscall.
                        int offset = 0;
                        NativeDatagramPacketArray.NativeDatagramPacket[] packets = array.packets();

                        while (cnt > 0) {
                            int send = socket.sendmmsg(packets, offset, cnt);
                            if (send == 0) {
                                // Did not write all messages.
                                setFlag(Native.EPOLLOUT);
                                return;
                            }
                            for (int i = 0; i < send; i++) {
                                in.remove();
                            }
                            cnt -= send;
                            offset += send;
                        }
                        continue;
                    }
                }
                boolean done = false;
                for (int i = config().getWriteSpinCount(); i > 0; --i) {
                    if (doWriteMessage(msg)) {
                        done = true;
                        break;
                    }
                }

                if (done) {
                    in.remove();
                } else {
                    // Did not write all messages.
                    setFlag(Native.EPOLLOUT);
                    break;
                }
            } catch (IOException e) {
                // Continue on write error as a DatagramChannel can write to multiple remote peers
                //
                // See https://github.com/netty/netty/issues/2665
                in.remove(e);
            }
        }
    }

    private boolean doWriteMessage(Object msg) throws Exception {
        final ByteBuf data;
        InetSocketAddress remoteAddress;
        if (msg instanceof AddressedEnvelope) {
            @SuppressWarnings("unchecked")
            AddressedEnvelope<ByteBuf, InetSocketAddress> envelope =
                    (AddressedEnvelope<ByteBuf, InetSocketAddress>) msg;
            data = envelope.content();
            remoteAddress = envelope.recipient();
        } else {
            data = (ByteBuf) msg;
            remoteAddress = null;
        }

        final int dataLen = data.readableBytes();
        if (dataLen == 0) {
            return true;
        }

        final long writtenBytes;
        if (data.hasMemoryAddress()) {
            long memoryAddress = data.memoryAddress();
            if (remoteAddress == null) {
                writtenBytes = socket.writeAddress(memoryAddress, data.readerIndex(), data.writerIndex());
            } else {
                writtenBytes = socket.sendToAddress(memoryAddress, data.readerIndex(), data.writerIndex(),
                        remoteAddress.getAddress(), remoteAddress.getPort());
            }
        } else if (data.nioBufferCount() > 1) {
            IovArray array = ((EpollEventLoop) eventLoop()).cleanIovArray();
            array.add(data);
            int cnt = array.count();
            assert cnt != 0;

            if (remoteAddress == null) {
                writtenBytes = socket.writevAddresses(array.memoryAddress(0), cnt);
            } else {
                writtenBytes = socket.sendToAddresses(array.memoryAddress(0), cnt,
                        remoteAddress.getAddress(), remoteAddress.getPort());
            }
        } else  {
            ByteBuffer nioData = data.internalNioBuffer(data.readerIndex(), data.readableBytes());
            if (remoteAddress == null) {
                writtenBytes = socket.write(nioData, nioData.position(), nioData.limit());
            } else {
                writtenBytes = socket.sendTo(nioData, nioData.position(), nioData.limit(),
                        remoteAddress.getAddress(), remoteAddress.getPort());
            }
        }

        return writtenBytes > 0;
    }

    @Override
    protected Object filterOutboundMessage(Object msg) {
        if (msg instanceof DatagramPacket) {
            DatagramPacket packet = (DatagramPacket) msg;
            ByteBuf content = packet.content();
            return UnixChannelUtil.isBufferCopyNeededForWrite(content) ?
                    new DatagramPacket(newDirectBuffer(packet, content), packet.recipient()) : msg;
        }

        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            return UnixChannelUtil.isBufferCopyNeededForWrite(buf)? newDirectBuffer(buf) : buf;
        }

        if (msg instanceof AddressedEnvelope) {
            @SuppressWarnings("unchecked")
            AddressedEnvelope<Object, SocketAddress> e = (AddressedEnvelope<Object, SocketAddress>) msg;
            if (e.content() instanceof ByteBuf &&
                (e.recipient() == null || e.recipient() instanceof InetSocketAddress)) {

                ByteBuf content = (ByteBuf) e.content();
                return UnixChannelUtil.isBufferCopyNeededForWrite(content)?
                        new DefaultAddressedEnvelope<ByteBuf, InetSocketAddress>(
                            newDirectBuffer(e, content), (InetSocketAddress) e.recipient()) : e;
            }
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    @Override
    public EpollDatagramChannelConfig config() {
        return config;
    }

    @Override
    protected void doDisconnect() throws Exception {
        socket.disconnect();
        connected = active = false;
    }

    @Override
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        if (super.doConnect(remoteAddress, localAddress)) {
            connected = true;
            return true;
        }
        return false;
    }

    @Override
    protected void doClose() throws Exception {
        super.doClose();
        connected = false;
    }

    final class EpollDatagramChannelUnsafe extends AbstractEpollUnsafe {

        @Override
        void epollInReady() {
            assert eventLoop().inEventLoop();
            DatagramChannelConfig config = config();
            if (shouldBreakEpollInReady(config)) {
                clearEpollIn0();
                return;
            }
            final EpollRecvByteAllocatorHandle allocHandle = recvBufAllocHandle();
            allocHandle.edgeTriggered(isFlagSet(Native.EPOLLET));

            final ChannelPipeline pipeline = pipeline();
            final ByteBufAllocator allocator = config.getAllocator();
            allocHandle.reset(config);
            epollInBefore();

            Throwable exception = null;
            try {
                ByteBuf byteBuf = null;
                try {
                    boolean connected = isConnected();
                    do {
                        byteBuf = allocHandle.allocate(allocator);
                        allocHandle.attemptedBytesRead(byteBuf.writableBytes());

                        final DatagramPacket packet;
                        if (connected) {
                            try {
                                allocHandle.lastBytesRead(doReadBytes(byteBuf));
                            } catch (Errors.NativeIoException e) {
                                // We need to correctly translate connect errors to match NIO behaviour.
                                if (e.expectedErr() == Errors.ERROR_ECONNREFUSED_NEGATIVE) {
                                    PortUnreachableException error = new PortUnreachableException(e.getMessage());
                                    error.initCause(e);
                                    throw error;
                                }
                                throw e;
                            }
                            if (allocHandle.lastBytesRead() <= 0) {
                                // nothing was read, release the buffer.
                                byteBuf.release();
                                byteBuf = null;
                                break;
                            }
                            packet = new DatagramPacket(
                                    byteBuf, (InetSocketAddress) localAddress(), (InetSocketAddress) remoteAddress());
                        } else {
                            final DatagramSocketAddress remoteAddress;
                            if (byteBuf.hasMemoryAddress()) {
                                // has a memory address so use optimized call
                                remoteAddress = socket.recvFromAddress(byteBuf.memoryAddress(), byteBuf.writerIndex(),
                                        byteBuf.capacity());
                            } else {
                                ByteBuffer nioData = byteBuf.internalNioBuffer(
                                        byteBuf.writerIndex(), byteBuf.writableBytes());
                                remoteAddress = socket.recvFrom(nioData, nioData.position(), nioData.limit());
                            }

                            if (remoteAddress == null) {
                                allocHandle.lastBytesRead(-1);
                                byteBuf.release();
                                byteBuf = null;
                                break;
                            }
                            InetSocketAddress localAddress = remoteAddress.localAddress();
                            if (localAddress == null) {
                                localAddress = (InetSocketAddress) localAddress();
                            }
                            allocHandle.lastBytesRead(remoteAddress.receivedAmount());
                            byteBuf.writerIndex(byteBuf.writerIndex() + allocHandle.lastBytesRead());

                            packet = new DatagramPacket(byteBuf, localAddress, remoteAddress);
                        }

                        allocHandle.incMessagesRead(1);

                        readPending = false;
                        pipeline.fireChannelRead(packet);

                        byteBuf = null;
                    } while (allocHandle.continueReading());
                } catch (Throwable t) {
                    if (byteBuf != null) {
                        byteBuf.release();
                    }
                    exception = t;
                }

                allocHandle.readComplete();
                pipeline.fireChannelReadComplete();

                if (exception != null) {
                    pipeline.fireExceptionCaught(exception);
                }
            } finally {
                epollInFinally(config);
            }
        }
    }
}
