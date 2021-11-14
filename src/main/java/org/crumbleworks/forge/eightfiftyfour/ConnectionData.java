package org.crumbleworks.forge.eightfiftyfour;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import org.crumbleworks.forge.eightfiftyfour.processing.TelnetSession;

/**
 * Holds all the data the server needs for handling a connection.
 *
 * @author Michael Stocker
 * @since 0.1.0
 */
public final class ConnectionData {

    protected final TelnetSession telSess;

    protected final PipedInputStream incomingDataRead; // processor-task
    protected final PipedOutputStream incomingDataWrite;

    protected final PipedInputStream outgoingDataRead;
    protected final PipedOutputStream outgoingDataWrite; // processor-task

    public ConnectionData(TelnetSession telSess) throws IOException {
        this.telSess = telSess;

        incomingDataRead = new PipedInputStream();
        incomingDataWrite = new PipedOutputStream(incomingDataRead);
        outgoingDataRead = new PipedInputStream();
        outgoingDataWrite = new PipedOutputStream(outgoingDataRead);
    }
}
