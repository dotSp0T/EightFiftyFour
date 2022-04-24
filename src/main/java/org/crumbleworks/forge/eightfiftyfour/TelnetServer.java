package org.crumbleworks.forge.eightfiftyfour;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.crumbleworks.forge.eightfiftyfour.processing.TelnetProcessor;
import org.crumbleworks.forge.eightfiftyfour.processing.TelnetSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO [high] add code (and ctor param) for session timeout durations;
// duration after which a session will timeout if it has not received any data
// from the client
/**
 * Each server instance handles incoming connections on any number of ports
 * given. A new worker-thread is spawned for each port the instance is
 * listening on, so to be able to quickly react to new connections.
 * <p>
 * Connections are then handled using NIO {@link SocketChannel}s with a
 * scheduler thread delegating processing tasks to a thread-pool which can be
 * further configured using the advanced constructors.
 * 
 * @author Michael Stocker
 * @since 0.1.0
 */
public class TelnetServer implements Closeable {

    public static final int DEFAULT_NUM_WORKERS_PER_PORT = 3;

    public static final String SOCKET_THREAD_NAME = "Telnet:";
    public static final String SCHEDULER_THREAD_NAME = "Telnet-Scheduler";

    private static final Logger logger = LoggerFactory
            .getLogger(TelnetServer.class);

    private final int[] ports;
    private final ScheduledExecutorService threadpool;
    private final TelnetProcessor procImpl;

    private Map<ServerSocketChannel, Thread> serverSockets;
    private Thread schedulerThread;

    private final Selector connectionSelector;

    /**
     * @param ports
     *            a list of ports the server is supposed to handle
     * @param procImpl
     *            the {@link TelnetProcessor} responsible for server-logic
     * @throws ServerSetupException
     *             any of the given ports fail to establish a
     *             socket-connection
     */
    public TelnetServer(int[] ports, TelnetProcessor procImpl) {
        this(ports, procImpl, ports.length * DEFAULT_NUM_WORKERS_PER_PORT);
    }

    /**
     * @param ports
     *            a list of ports the server is supposed to handle
     * @param procImpl
     *            the {@link TelnetProcessor} responsible for server-logic
     * @param threads
     *            the minimum amount of worker-threads being kept alive at all
     *            time for connection handling
     * @throws ServerSetupException
     *             any of the given ports fail to establish a
     *             socket-connection
     */
    public TelnetServer(int[] ports, TelnetProcessor procImpl, int threads) {
        this(ports, procImpl, Executors.newScheduledThreadPool(threads));
    }

    /**
     * @param ports
     *            a list of ports the server is supposed to handle
     * @param procImpl
     *            the {@link TelnetProcessor} responsible for server-logic
     * @param threadpool
     *            a custom {@link ScheduledExecutorService} for processing
     * @throws ServerSetupException
     *             any of the given ports fail to establish a
     *             socket-connection
     */
    public TelnetServer(int[] ports, TelnetProcessor procImpl,
            ScheduledExecutorService threadpool) {
        this.ports = Util.assignOrThrow(ports,
                "Passed ports-array may not be null");
        this.threadpool = Util.assignOrThrow(threadpool,
                "Passed threadpool may not be null");
        this.procImpl = Util.assignOrThrow(procImpl,
                "Passed TelnetProcessor may not be null");

        try {
            connectionSelector = Selector.open();
        } catch(IOException e) {
            throw new ServerSetupException(e);
        }
    }

    /**
     * Starts up the server, spawning all the threads.
     */
    public void start() {
        Map<ServerSocketChannel, Thread> serverSockets = new HashMap<>();
        for(int port : ports) {
            ServerSocketChannel socket;
            try {
                socket = ServerSocketChannel.open();
                socket.bind(new InetSocketAddress(port));
                socket.configureBlocking(true);
            } catch(IOException e) {
                throw new ServerSetupException(e);
            }

            Thread thread = new Thread(new PortListener(port, socket),
                    SOCKET_THREAD_NAME + port);
            serverSockets.put(socket, thread);
            thread.start();
        }

        this.serverSockets = Collections.unmodifiableMap(serverSockets);

        schedulerThread = new Thread(this::schedule, SCHEDULER_THREAD_NAME);
        schedulerThread.start();
    }

    @Override
    public void close() throws IOException {
        for(Entry<ServerSocketChannel, Thread> entry : serverSockets
                .entrySet()) {
            entry.getKey().close();
        }

        connectionSelector.close();
        // TODO [high] make sure every TelnetSession has been killed and torn
        // down
        threadpool.shutdownNow();
    }

    private final void schedule() {
        logger.info("Start scheduling");
        while(connectionSelector.isOpen()) {
            Thread.yield();

            try {
                connectionSelector.select();
            } catch(ClosedSelectorException e) {
                logger.info("NIO Selector has been closed.");
                break;
            } catch(IOException e) {
                new RuntimeException(e);
            }

            for(SelectionKey key : connectionSelector.selectedKeys()) {
                if(!key.isValid()) {
                    continue;
                }

                SocketChannel channel = (SocketChannel)key.channel();
                ConnectionData data = (ConnectionData)key.attachment();

                if(!key.channel().isOpen()) {
                    data.telSess.kill();
                    return; // already closed, it's going to die any moment
                }

                //TODO lock READ to once at a time; drop others
                if(key.isReadable()) {
                    threadpool.execute(() -> incomingRead(channel, data));
                }

                //TODO lock WRITE to once at a time; drop others
                if(key.isWritable()) {
                    threadpool.execute(() -> outgoingWrite(channel, data));
                }
            }
        }
        logger.info("Stop scheduling. Selector has been closed.");
    }

    private final void incomingRead(SocketChannel channel, ConnectionData data) {
        try {
            var bytesRead = channel.read(data.incomingBuffer);
            if(-1 == bytesRead) {
                channel.close();
                logger.info("Connection to {} has been closed by the client.", channel);
                data.telSess.kill();

                // TODO [low] cleanup logic into methods, to reduce code duplication make sure we trigger the execution to finish processing the session
                if(data.isWaitingForInput.getAndSet(false)) {
                    threadpool.execute(() -> process(data));
                }

                return;
            }

            if(0 == bytesRead) {
                return; // nothing incoming
            }

            data.incomingBuffer.flip();

            if(logger.isTraceEnabled()) {
                int prevPos = data.incomingBuffer.position();
                logger.trace("Reading from {}: {}", channel.getRemoteAddress(), StandardCharsets.UTF_8.decode(data.incomingBuffer).toString().trim());
                data.incomingBuffer.position(prevPos);
            }

            // TODO [high] do any telnet protocol processing here

            // pass all leftover data to the processor
            data.incomingData.sink().write(data.incomingBuffer);
            data.incomingBuffer.clear();

            if(data.isWaitingForInput.getAndSet(false)) {
                threadpool.execute(() -> process(data));
            }
        } catch(IOException e) {
            logger.warn("Exception while reading from {}; Aborting connection.", channel, e);
            try {
                channel.close();
            } catch(IOException f) {
                // drop exception, just make sure it is closed
            }
        }
    }
    
    private void outgoingWrite(SocketChannel channel, ConnectionData data) {
        try {
            data.outgoingData.source().read(data.outgoingBuffer);
            data.outgoingBuffer.flip();

            if(!data.outgoingBuffer.hasRemaining()) {
                return; // nothing outgoing
            }
            System.out.println(">>> 1 pos: " + data.outgoingBuffer.position() + "; limit: " + data.outgoingBuffer.limit());

            if(logger.isTraceEnabled()) {
                int prevPos = data.outgoingBuffer.position();
                logger.trace("Sending to {}: {}", channel.getRemoteAddress(), StandardCharsets.UTF_8.decode(data.outgoingBuffer).toString().trim());
                data.outgoingBuffer.position(prevPos);
            }
            System.out.println(">>> 2 pos: " + data.outgoingBuffer.position() + "; limit: " + data.outgoingBuffer.limit());

            channel.write(data.outgoingBuffer);
            data.outgoingBuffer.clear();
            System.out.println(">>> 3 pos: " + data.outgoingBuffer.position() + "; limit: " + data.outgoingBuffer.limit());
        } catch(IOException e) {
            logger.warn("Exception while writing to {}; Aborting connection.", channel, e);
            try {
                channel.close();
            } catch(IOException f) {
                // drop exception, just make sure it is closed
            }
        }
    }

    private final class PortListener implements Runnable {

        private final int port;
        private final ServerSocketChannel socket;

        public PortListener(int port, ServerSocketChannel socket) {
            this.port = port;
            this.socket = socket;
        }

        @Override
        public void run() {
            logger.info("Start listening for new connections on port {}",
                    port);
            while(socket.isOpen()) {
                SocketChannel clientConnection = null;
                try {
                    clientConnection = socket.accept();
                    clientConnection.configureBlocking(false);
                } catch(ClosedChannelException e) {
                    logger.info("Socket on port {} has been closed.", port);
                    break;
                } catch(IOException e) {
                    logger.error(
                            "Failed to establish Client-Connection on port: {}",
                            port, e);
                }

                var telSess = new TelnetSession(port,
                        clientConnection.socket().getInetAddress());
                logger.info("New connection from {} on port {}",
                        telSess.address(), port);

                ConnectionData data;
                try {
                    data = new ConnectionData(telSess);
                    clientConnection.register(connectionSelector,
                            SelectionKey.OP_READ | SelectionKey.OP_WRITE,
                            data);
                    connectionSelector.wakeup();
                } catch(ClosedChannelException e) {
                    logger.error("Client-Connection has been closed.");
                    continue;
                } catch(IOException e) {
                    try {
                        clientConnection.close();
                    } catch(IOException f) {
                        // drop exception, might've been closed already or
                        // never opened
                    }

                    logger.error(
                            "Error while trying to create local data channels. Connection aborted.");
                    continue;
                }

                threadpool.execute(() -> {
                    procImpl.setup(data.telSess);
                    process(data);
                });

            }

            logger.info("Stop listening for new connections on port {}",
                    port);
        }
    }

    private final void process(ConnectionData data) {
        if(data.telSess.wasKilled()) {
            procImpl.teardown(data.incomingRead, data.telSess);
            return;
        }

        var result = procImpl.process(data.incomingRead, data.outgoingWrite,
                data.telSess);

        if(-1 == result) {
            data.isWaitingForInput.set(true);
            return; // will be triggered by inputprocessing
        }

        if(0 < result) {
            threadpool.schedule(() -> process(data), result,
                    TimeUnit.MILLISECONDS);
            return;
        }

        threadpool.execute(() -> process(data));
    }
}
