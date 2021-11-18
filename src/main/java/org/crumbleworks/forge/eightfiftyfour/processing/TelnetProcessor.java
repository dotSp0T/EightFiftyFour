package org.crumbleworks.forge.eightfiftyfour.processing;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Represents a collection of methods that are used to process incoming data,
 * create outgoing data and do other operations.
 * <p>
 * An implementation of this class is the basis for the logic of your
 * telnet-server.
 * 
 * @author Michael Stocker
 * @since 0.1.0
 */
public interface TelnetProcessor {

    /**
     * Allows setting up a new telnetsession. E.g. acquiring resources or
     * loading any persistent state.
     * 
     * @param data
     *            an information-object associated with the connection that is
     *            currently being processed
     */
    default void setup(TelnetSession data) {}

    /**
     * Allows processing incoming data and sending outgoing data to the
     * connected client.
     * <p>
     * This is the <em>workhorse</em> method. It is responsible for actually
     * applying any logic based on the received inputs, and produce outputs.
     * <p>
     * The implementation for an echo server for example, might simply read
     * the given inputstream and write it instantly to the outputstream again.
     * <p>
     * <em>Note:</em> while you can implement your logic in a blocking mode,
     * waiting on inputs from the inputstream, this method is designed to
     * allow a non-blocking model of processing. That means a valid
     * implementation might read as much from the inputstream as possible,
     * process whatever it can, store the unprocessed rest in a buffer or
     * similar, and return with instructions on when to start the next
     * processing cycle.
     * 
     * @param in
     *            an {@link InputStream} holding any data received from the
     *            other side of the connection
     * @param out
     *            an {@link OutputStream} whose contents will eventually be
     *            sent to the other side of the connection
     * @param data
     *            an information-object associated with the connection that is
     *            currently being processed
     * @return either <code>-1</code>, <code>0</code> or a positive number.
     *         Any other negative value will be interpreted as <code>0</code>.
     *         The value <code>-1</code> indicates that processing should be
     *         suspended until userinput is received. The value <code>0</code>
     *         indicates that the next processing cycle should be started
     *         ASAP. Any positive value is treated as a timeout in
     *         milliseconds (after the end of this processing cycle) after
     *         which the next processing cycle should be started.
     */
    long process(InputStream in, OutputStream out, TelnetSession data);

    /**
     * Allows cleaning up a killed telnetsession. E.g. releasing resources or
     * storing any persistent state.*
     * 
     * @param in
     *            an {@link InputStream} holding any data received from the
     *            other side of the connection
     * @param data
     *            an information-object associated with the connection that is
     *            currently being processed
     */
    default void teardown(InputStream in, TelnetSession data) {}
}
