package com.stock_spoon.river_be.market;

import java.io.IOException;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;

/** 테스트 BE의 연결만 끊는다. 기존 Valkey 컨테이너와 데이터는 변경하지 않는다. */
final class RedisFaultProxy implements AutoCloseable {
    private final ServerSocket listener;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean available = true;
    RedisFaultProxy() throws IOException {
        listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        workers.submit(() -> {
            while (!listener.isClosed()) {
                try {
                    Socket downstream = listener.accept();
                    sockets.add(downstream);
                    workers.submit(() -> connect(downstream));
                } catch (IOException failure) {
                    if (!listener.isClosed()) throw new IllegalStateException(failure);
                }
            }
        });
    }
    int port() { return listener.getLocalPort(); }
    synchronized void disconnect() {
        available = false;
        sockets.forEach(RedisFaultProxy::closeSocket);
    }
    synchronized void reconnect() { available = true; }
    private void connect(Socket downstream) {
        Socket upstream = null;
        try {
            if (!available) return;
            upstream = new Socket();
            upstream.connect(new InetSocketAddress("localhost", 6380), 3000);
            sockets.add(upstream);
            synchronized (this) {
                if (!available) return;
            }
            Socket remote = upstream;
            workers.submit(() -> copy(remote, downstream));
            copy(downstream, upstream);
        } catch (IOException ignored) {
            // 연결 거절·중단은 의도한 장애다. 정상 경로는 실제 BE의 Redis 읽기/쓰기로 검증한다.
        } finally {
            closeSocket(downstream); sockets.remove(downstream);
            if (upstream != null) { closeSocket(upstream); sockets.remove(upstream); }
        }
    }
    private static void copy(Socket from, Socket to) {
        try { from.getInputStream().transferTo(to.getOutputStream()); }
        catch (IOException ignored) { }
        finally { closeSocket(from); closeSocket(to); }
    }
    private static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }
    public void close() throws IOException {
        available = false; listener.close(); sockets.forEach(RedisFaultProxy::closeSocket);
        workers.shutdownNow();
    }
}
