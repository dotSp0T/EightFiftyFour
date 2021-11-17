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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.crumbleworks.forge.eightfiftyfour.processing.TelnetProcessor;
import org.crumbleworks.forge.eightfiftyfour.processing.TelnetSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//TODO [high] add code (and ctor param) for session timeout durations; duration after which a session will timeout if it has not received any data from the client
/**
 * Each server instance handles incoming connections on any number of ports
 * given. A new worker-thread is spawned for each port the instance is
 * listening on, so to be able to quickly react to new connections.
 * <p>
 * Connections are then handled using NIO {@link SocketChannel}s with a
 * scheduler thread delegating input-processing tasks to a thread-pool which
 * can be further configured using the advanced constructors.
 *
 * @author Michael Stocker
 * @since 0.1.0
 */
public class TelnetServer implements Closeable {

    public static final int DEFAULT_NUM_WORKERS_PER_PORT = 5;
    public static final int DEFAULT_WORKER_IDLING_THRESHOLD_IN_MILLISECONDS = 500;

    public static final String SOCKET_THREAD_NAME = "Telnet:";
    public static final String SCHEDULER_THREAD_NAME = "Telnet-Scheduler";

    private static final Logger logger = LoggerFactory
            .getLogger(TelnetServer.class);

    private final int[] ports;
    private final ExecutorService threadpool;
    private final TelnetProcessor procImpl;
    private Map<ServerSocketChannel, Thread> serverSockets;

    private final Selector connectionSelector;

    /**
     * Assigns at most <code>ports *</code>
     * {@link #DEFAULT_NUM_WORKERS_PER_PORT} worker-threads for connection
     * handling.
     * 
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
     * @param workers
     *            the maximal amount of worker-threads used to handle
     *            connections
     * @throws ServerSetupException
     *             any of the given ports fail to establish a
     *             socket-connection
     */
    public TelnetServer(int[] ports, TelnetProcessor procImpl, int workers) {
        this(ports, procImpl, ports.length,
                ports.length * DEFAULT_NUM_WORKERS_PER_PORT,
                DEFAULT_WORKER_IDLING_THRESHOLD_IN_MILLISECONDS);
    }

    /**
     * @param ports
     *            a list of ports the server is supposed to handle
     * @param procImpl
     *            the {@link TelnetProcessor} responsible for server-logic
     * @param minworkers
     *            the minimum amount of worker-threads being kept alive at all
     *            time for connection handling
     * @param maxworkers
     *            the maximum amount of worker-threads that may be in use
     *            concurrently for connection handling
     * @param threshold
     *            an amount of <code>milliseconds</code> worker-threads may be
     *            idling before they are torn down again
     * @throws ServerSetupException
     *             any of the given ports fail to establish a
     *             socket-connection
     */
    public TelnetServer(int[] ports, TelnetProcessor procImpl, int minworkers,
            int maxworkers, int threshold) {
        this(ports, procImpl,
                new ThreadPoolExecutor(minworkers, maxworkers, threshold,
                        TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()));
    }

    /**
     * @param ports
     *            a list of ports the server is supposed to handle
     * @param procImpl
     *            the {@link TelnetProcessor} responsible for server-logic
     * @param threadpool
     *            a custom {@link ExecutorService} for connection-handling
     * @throws ServerSetupException
     *             any of the given ports fail to establish a
     *             socket-connection
     */
    public TelnetServer(int[] ports, TelnetProcessor procImpl, ExecutorService threadpool) {
    	this.ports = Util.assignOrThrow(ports, "Passed ports-array may not be null");
        this.threadpool = Util.assignOrThrow(threadpool, "Passed threadpool may not be null");
        this.procImpl = Util.assignOrThrow(procImpl, "Passed TelnetProcessor may not be null");
        
        try {
            connectionSelector = Selector.open();
        } catch(IOException e) {
            throw new ServerSetupException(e);
        }
    }
    
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
    		
    		Thread thread = new Thread(new PortListener(port, socket), SOCKET_THREAD_NAME + port);
    		serverSockets.put(socket, thread);
    		thread.start();
    	}
    	
    	this.serverSockets = Collections.unmodifiableMap(serverSockets);
    	schedule(); //get busy
	}

    @Override
    public void close() throws IOException {
        for(Entry<ServerSocketChannel, Thread> entry : serverSockets
                .entrySet()) {
            entry.getKey().close();
        }

        connectionSelector.close();
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
                
            	if(!key.channel().isOpen()) {
            		return; //already closed, it's going to die any moment
            	}

                if(key.isReadable()) {
                    threadpool.execute(() -> {
                    	SocketChannel channel = (SocketChannel)key.channel();
                    	ConnectionData data = (ConnectionData)key.attachment();
                    	
                    	try {
                    		var bytesRead = channel.read(data.incomingBuffer);
							if(-1 == bytesRead) {
								channel.close();
								logger.info("Connection to {} has been closed by the client.", channel);
								return;
							}
							
							if(0 == bytesRead) {
								return; //nothing incoming
							}
							
	                        data.incomingBuffer.flip();
	                        
	                        if(logger.isTraceEnabled()) {
	                        	logger.trace("Reading from {}: {}", channel, StandardCharsets.UTF_8.decode(data.incomingBuffer).toString().trim());
	                        }
	                        
	                        //TODO [high] do any telnet protocol processing here
	                        
	                        //pass all leftover data to the processor
	                        data.incomingData.sink().write(data.incomingBuffer);
	                        data.incomingBuffer.clear();
                    	} catch (IOException e) {
                    		logger.warn("Exception while reading from {}; Aborting connection.", channel, e);
                    		try {
								channel.close();
							} catch (IOException f) {
			                    //drop exception, just make sure it is closed
							}
                    	}
                    });
                }

                if(key.isWritable()) {
                    threadpool.execute(() -> {
                        SocketChannel channel = (SocketChannel)key.channel();
                        ConnectionData data = (ConnectionData)key.attachment();
                        
                    	try {
                    		data.outgoingData.source().read(data.outgoingBuffer);
                    		data.outgoingBuffer.flip();
                    		
                    		if(!data.outgoingBuffer.hasRemaining()) {
                    			return; //nothing outgoing
                    		}
	                        
	                        if(logger.isTraceEnabled()) {
	                        	logger.trace("Sending to {}: {}", channel, StandardCharsets.UTF_8.decode(data.outgoingBuffer).toString().trim());
	                        }
                    		
                    		channel.write(data.outgoingBuffer);
                    		data.outgoingBuffer.clear();
                    	} catch (IOException e) {
                    		logger.warn("Exception while writing to {}; Aborting connection.", channel, e);
                    		try {
								channel.close();
							} catch (IOException f) {
			                    //drop exception, just make sure it is closed
							}
                    	}
                    });
                }
            }
        }
        logger.info("Stop scheduling. Selector has been closed.");
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
			logger.info("Start listening for new connections on port {}", port);
    		while(socket.isOpen()) {
	    		SocketChannel clientConnection = null;
	            try {
	                clientConnection = socket.accept();
	                clientConnection.configureBlocking(false);
	            } catch(ClosedChannelException e) {
	                logger.info("Socket on port {} has been closed.", port);
	                break;
	            } catch(IOException e) {
	                logger.error("Failed to establish Client-Connection on port: {}", port, e);
	            }
	
	            try {
	                TelnetSession telSess = new TelnetSession(port, clientConnection.socket().getInetAddress());
	                logger.info("New connection from {} on port {}", telSess.address(), port);
	                clientConnection.register(connectionSelector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, new ConnectionData(telSess));
	                connectionSelector.wakeup();
	            } catch(ClosedChannelException e) {
	                logger.error("Client-Connection has been closed.");
	                continue;
	            } catch(IOException e) {
	                try {
	                    clientConnection.close();
	                } catch(IOException f) {
	                    //drop exception, might've been closed already or never opened
	                }
	                
	                logger.error("Error while trying to create local data channels. Connection aborted.");
	                continue;
	            }

	            // TODO schedule FIRST process-task (the rest will just keep calling each other?)
	            // procImpl.process(data.incomingDataRead, data.outgoingDataWrite, data.telSess);
	            // -> something with returned time, maybe work with futures?
	        }
    		
    		logger.info("Stop listening for new connections on port {}", port);
    	}
    }
}
