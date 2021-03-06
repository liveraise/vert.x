/*
 * Copyright (c) 2011-2013 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package io.vertx.core.datagram.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.logging.LoggingHandler;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.impl.Arguments;
import io.vertx.core.impl.ContextImpl;
import io.vertx.core.impl.NettyTransportFactory;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.net.impl.ConnectionBase;
import io.vertx.core.net.impl.PartialPooledByteBufAllocator;
import io.vertx.core.net.impl.SocketAddressImpl;
import io.vertx.core.spi.metrics.DatagramSocketMetrics;
import io.vertx.core.spi.metrics.Metrics;
import io.vertx.core.spi.metrics.MetricsProvider;
import io.vertx.core.spi.metrics.NetworkMetrics;
import io.vertx.core.spi.metrics.VertxMetrics;
import io.vertx.core.streams.WriteStream;

import java.net.*;
import java.util.Objects;

/**
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
public class DatagramSocketImpl implements DatagramSocket, MetricsProvider {

  public static DatagramSocketImpl create(VertxInternal vertx, DatagramSocketOptions options) {
    DatagramSocketImpl socket = new DatagramSocketImpl(vertx, options);
    // Make sure object is fully initiliased to avoid race with async registration
    socket.init();
    return socket;
  }

  private final ContextImpl context;
  private final DatagramSocketMetrics metrics;
  private DatagramChannel channel;
  private Handler<io.vertx.core.datagram.DatagramPacket> packetHandler;
  private Handler<Void> endHandler;
  private Handler<Throwable> exceptionHandler;

  private DatagramSocketImpl(VertxInternal vertx, DatagramSocketOptions options) {
    DatagramChannel channel = createChannel(options.isIpV6() ? io.vertx.core.datagram.impl.InternetProtocolFamily.IPv6 : io.vertx.core.datagram.impl.InternetProtocolFamily.IPv4,
        new DatagramSocketOptions(options));

    ContextImpl context = vertx.getOrCreateContext();
    if (context.isMultiThreadedWorkerContext()) {
      throw new IllegalStateException("Cannot use DatagramSocket in a multi-threaded worker verticle");
    }
    channel.config().setOption(ChannelOption.DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION, true);
    channel.config().setMaxMessagesPerRead(1);
    channel.config().setAllocator(PartialPooledByteBufAllocator.INSTANCE);
    context.nettyEventLoop().register(channel);
    if (options.getLogActivity()) {
      channel.pipeline().addLast("logging", new LoggingHandler());
    }
    VertxMetrics metrics = vertx.metricsSPI();
    this.metrics = metrics != null ? metrics.createMetrics(this, options) : null;
    this.channel = channel;
    this.context = context;
  }

  private void init() {
    channel.pipeline().addLast("handler", new DatagramServerHandler(this));
  }

  @Override
  public DatagramSocket listenMulticastGroup(String multicastAddress, Handler<AsyncResult<DatagramSocket>> handler) {
    try {
      addListener(channel.joinGroup(InetAddress.getByName(multicastAddress)), handler);
    } catch (UnknownHostException e) {
      notifyException(handler, e);
    }
    return this;
  }

  @Override
  public DatagramSocket listenMulticastGroup(String multicastAddress, String networkInterface, String source, Handler<AsyncResult<DatagramSocket>> handler) {
    try {
      InetAddress sourceAddress;
      if (source == null) {
        sourceAddress = null;
      } else {
        sourceAddress = InetAddress.getByName(source);
      }
      addListener(channel.joinGroup(InetAddress.getByName(multicastAddress),
              NetworkInterface.getByName(networkInterface), sourceAddress), handler);
    } catch (Exception e) {
      notifyException(handler, e);
    }
    return this;
  }

  @Override
  public DatagramSocket unlistenMulticastGroup(String multicastAddress, Handler<AsyncResult<DatagramSocket>> handler) {
    try {
      addListener(channel.leaveGroup(InetAddress.getByName(multicastAddress)), handler);
    } catch (UnknownHostException e) {
      notifyException(handler, e);
    }
    return this;
  }

  @Override
  public DatagramSocket unlistenMulticastGroup(String multicastAddress, String networkInterface, String source, Handler<AsyncResult<DatagramSocket>> handler) {
    try {
      InetAddress sourceAddress;
      if (source == null) {
        sourceAddress = null;
      } else {
        sourceAddress = InetAddress.getByName(source);
      }
      addListener(channel.leaveGroup(InetAddress.getByName(multicastAddress),
              NetworkInterface.getByName(networkInterface), sourceAddress), handler);
    } catch (Exception e) {
      notifyException(handler, e);
    }
    return this;
  }

  @Override
  public DatagramSocket blockMulticastGroup(String multicastAddress, String networkInterface, String sourceToBlock, Handler<AsyncResult<DatagramSocket>> handler) {
    try {
      InetAddress sourceAddress;
      if (sourceToBlock == null) {
        sourceAddress = null;
      } else {
        sourceAddress = InetAddress.getByName(sourceToBlock);
      }
      addListener(channel.block(InetAddress.getByName(multicastAddress),
              NetworkInterface.getByName(networkInterface), sourceAddress), handler);
    } catch (Exception e) {
      notifyException(handler, e);
    }
    return  this;
  }

  @Override
  public DatagramSocket blockMulticastGroup(String multicastAddress, String sourceToBlock, Handler<AsyncResult<DatagramSocket>> handler) {
    try {
      addListener(channel.block(InetAddress.getByName(multicastAddress), InetAddress.getByName(sourceToBlock)), handler);
    } catch (UnknownHostException e) {
      notifyException(handler, e);
    }
    return this;
  }

  @Override
  public DatagramSocket listen(int port, String address, Handler<AsyncResult<DatagramSocket>> handler) {
    return listen(new SocketAddressImpl(port, address), handler);
  }

  @Override
  public synchronized DatagramSocket handler(Handler<io.vertx.core.datagram.DatagramPacket> handler) {
    this.packetHandler = handler;
    return this;
  }

  @Override
  public DatagramSocketImpl endHandler(Handler<Void> handler) {
    endHandler = handler;
    return this;
  }

  @Override
  public DatagramSocketImpl exceptionHandler(Handler<Throwable> handler) {
    exceptionHandler = handler;
    return this;
  }

  private DatagramSocket listen(SocketAddress local, Handler<AsyncResult<DatagramSocket>> handler) {
    Objects.requireNonNull(handler, "no null handler accepted");
    context.owner().resolveAddress(local.host(), res -> {
      if (res.succeeded()) {
        ChannelFuture future = channel.bind(new InetSocketAddress(res.result(), local.port()));
        addListener(future, ar -> {
          if (metrics != null && ar.succeeded()) {
            metrics.listening(local.host(), localAddress());
          }
          handler.handle(ar);
        });
      } else {
        handler.handle(Future.failedFuture(res.cause()));
      }
    });

    return this;
  }

  @SuppressWarnings("unchecked")
  final void addListener(ChannelFuture future, Handler<AsyncResult<DatagramSocket>> handler) {
    if (handler != null) {
      future.addListener(new DatagramChannelFutureListener<>(this, handler, context));
    }
  }

  @SuppressWarnings("unchecked")
  public DatagramSocket pause() {
    channel.config().setAutoRead(false);
    return this;
  }

  @SuppressWarnings("unchecked")
  public DatagramSocket resume() {
    channel.config().setAutoRead(true);
    return this;
  }

  @Override
  @SuppressWarnings("unchecked")
  public DatagramSocket send(Buffer packet, int port, String host, Handler<AsyncResult<DatagramSocket>> handler) {
    Objects.requireNonNull(packet, "no null packet accepted");
    Objects.requireNonNull(host, "no null host accepted");
    InetSocketAddress addr = InetSocketAddress.createUnresolved(host, port);
    if (addr.isUnresolved()) {
      context.owner().resolveAddress(host, res -> {
        if (res.succeeded()) {
          doSend(packet, new InetSocketAddress(res.result(), port), handler);
        } else {
          handler.handle(Future.failedFuture(res.cause()));
        }
      });
    } else {
      // If it's immediately resolved it means it was just an IP address so no need to async resolve
      doSend(packet, addr, handler);
    }
    if (metrics != null) {
      metrics.bytesWritten(null, new SocketAddressImpl(port, host), packet.length());
    }
    return this;
  }

  private void doSend(Buffer packet, InetSocketAddress addr, Handler<AsyncResult<DatagramSocket>> handler) {
    ChannelFuture future = channel.writeAndFlush(new DatagramPacket(packet.getByteBuf(), addr));
    addListener(future, handler);
  }

  @Override
  public WriteStream<Buffer> sender(int port, String host) {
    Arguments.requireInRange(port, 0, 65535, "port p must be in range 0 <= p <= 65535");
    Objects.requireNonNull(host, "no null host accepted");
    return new PacketWriteStreamImpl(this, port, host);
  }

  @Override
  public DatagramSocket send(String str, int port, String host, Handler<AsyncResult<DatagramSocket>> handler) {
    return send(Buffer.buffer(str), port, host, handler);
  }

  @Override
  public DatagramSocket send(String str, String enc, int port, String host, Handler<AsyncResult<DatagramSocket>> handler) {
    return send(Buffer.buffer(str, enc), port, host, handler);
  }

  @Override
  public SocketAddress localAddress() {
    InetSocketAddress addr = channel.localAddress();
    return new SocketAddressImpl(addr);
  }

  @Override
  public void close() {
    close(null);
  }

  @Override
  public synchronized void close(final Handler<AsyncResult<Void>> handler) {
    // make sure everything is flushed out on close
    if (!channel.isOpen()) {
      return;
    }
    channel.flush();
    ChannelFuture future = channel.close();
    if (handler != null) {
      future.addListener(new DatagramChannelFutureListener<>(null, handler, context));
    }
  }

  @Override
  public boolean isMetricsEnabled() {
    return metrics != null;
  }

  @Override
  public Metrics getMetrics() {
    return metrics;
  }

  private static DatagramChannel createChannel(io.vertx.core.datagram.impl.InternetProtocolFamily family,
                                                  DatagramSocketOptions options) {
      DatagramChannel channel;
    if (family == null) {
      channel = NettyTransportFactory.getDefaultFactory().instantiateDatagramChannel();
    } else {
      switch (family) {
        case IPv4:
          channel = NettyTransportFactory.getDefaultFactory().instantiateDatagramChannel(InternetProtocolFamily.IPv4);
          break;
        case IPv6:
          channel = NettyTransportFactory.getDefaultFactory().instantiateDatagramChannel(InternetProtocolFamily.IPv6);
          break;
        default:
          channel = NettyTransportFactory.getDefaultFactory().instantiateDatagramChannel();
      }
    }
    if (options.getSendBufferSize() != -1) {
      channel.config().setSendBufferSize(options.getSendBufferSize());
    }
    if (options.getReceiveBufferSize() != -1) {
      channel.config().setReceiveBufferSize(options.getReceiveBufferSize());
      channel.config().setRecvByteBufAllocator(new FixedRecvByteBufAllocator(options.getReceiveBufferSize()));
    }
    channel.config().setReuseAddress(options.isReuseAddress());
    if (options.getTrafficClass() != -1) {
      channel.config().setTrafficClass(options.getTrafficClass());
    }
    channel.config().setBroadcast(options.isBroadcast());
    channel.config().setLoopbackModeDisabled(options.isLoopbackModeDisabled());
    if (options.getMulticastTimeToLive() != -1) {
      channel.config().setTimeToLive(options.getMulticastTimeToLive());
    }
    if (options.getMulticastNetworkInterface() != null) {
      try {
        channel.config().setNetworkInterface(NetworkInterface.getByName(options.getMulticastNetworkInterface()));
      } catch (SocketException e) {
        throw new IllegalArgumentException("Could not find network interface with name " + options.getMulticastNetworkInterface());
      }
    }
    return channel;
  }

  private void notifyException(final Handler<AsyncResult<DatagramSocket>> handler, final Throwable cause) {
    context.executeFromIO(() -> handler.handle(Future.failedFuture(cause)));
  }

  @Override
  protected void finalize() throws Throwable {
    // Make sure this gets cleaned up if there are no more references to it
    // so as not to leave connections and resources dangling until the system is shutdown
    // which could make the JVM run out of file handles.
    close();
    super.finalize();
  }

  Connection createConnection(ChannelHandlerContext chctx) {
    return new Connection(context.owner(), chctx, context);
  }

  class Connection extends ConnectionBase {

    public Connection(VertxInternal vertx, ChannelHandlerContext channel, ContextImpl context) {
      super(vertx, channel, context);
    }

    @Override
    public NetworkMetrics metrics() {
      return metrics;
    }

    @Override
    protected void handleInterestedOpsChanged() {
    }

    @Override
    protected void handleException(Throwable t) {
      super.handleException(t);
      Handler<Throwable> handler;
      synchronized (DatagramSocketImpl.this) {
        handler = exceptionHandler;
      }
      if (handler != null) {
        handler.handle(t);
      }
    }

    @Override
    protected void handleClosed() {
      super.handleClosed();
      Handler<Void> handler;
      DatagramSocketMetrics metrics;
      synchronized (DatagramSocketImpl.this) {
        handler = endHandler;
        metrics = DatagramSocketImpl.this.metrics;
      }
      if (metrics != null) {
        metrics.close();
      }
      if (handler != null) {
        handler.handle(null);
      }
    }

    void handlePacket(io.vertx.core.datagram.DatagramPacket packet) {
      synchronized (DatagramSocketImpl.this) {
        if (metrics != null) {
          metrics.bytesRead(null, packet.sender(), packet.data().length());
        }
        if (packetHandler != null) {
          packetHandler.handle(packet);
        }
      }
    }
  }
}
