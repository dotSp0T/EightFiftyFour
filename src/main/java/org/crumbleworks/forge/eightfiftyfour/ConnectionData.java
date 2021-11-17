package org.crumbleworks.forge.eightfiftyfour;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;

import org.crumbleworks.forge.eightfiftyfour.processing.TelnetSession;

/**
 * Holds all the data the server needs for handling a connection.
 *
 * @author Michael Stocker
 * @since 0.1.0
 */
public final class ConnectionData {
	private final static int DEFAULT_BUFFER_SIZE = 1024;

    protected final TelnetSession telSess;

    
    protected final Pipe incomingData = Pipe.open();
    protected final ByteBuffer incomingBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
    protected final Pipe outgoingData = Pipe.open();
    protected final ByteBuffer outgoingBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);

    public ConnectionData(TelnetSession telSess) throws IOException {
        this.telSess = telSess;

        incomingData.sink().configureBlocking(false);
        incomingData.source().configureBlocking(false);
        outgoingData.sink().configureBlocking(false);
        outgoingData.source().configureBlocking(false);
    }
}
