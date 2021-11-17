package org.crumbleworks.forge.eightfiftyfour.processing;

import java.net.InetAddress;

//TODO [high] add method/flag to kill the session
//TODO [medium] add IP/Domainname to which the client session was built up (if possible?)
/**
 * Holds information about an established connection between server and
 * client. Additionally you can store your own data using the
 * {@link #setDataObject(Object)} and {@link #getDataObject()} methods.
 * <p>
 * The telnetsession object exists as long as the associated connection
 * between server and user is open and sending/receiving data.
 *
 * @author Michael Stocker
 * @since 0.1.0
 */
public final class TelnetSession {

    private final int originalPort;
    private final InetAddress clientAddress;
    private Object data;

    public TelnetSession(int originalPort, InetAddress clientAddress) {
        this.originalPort = originalPort;
        this.clientAddress = clientAddress;
    }

    /**
     * @return the port the client originally connected to
     */
    public final int port() {
        return originalPort;
    }

    /**
     * @return the address from which the client is connecting
     */
    public final InetAddress address() {
        return clientAddress;
    }

    /**
     * @return the data-object if any has been set using
     *         {@link #setDataObject(Object)}
     */
    public final Object getDataObject() {
        return data;
    }

    /**
     * This method allows you to associate a data-object with this
     * telnetsession. This allows you to store custom data without having to
     * manage your own storage structure.
     * 
     * @param object
     *            a data-object to be associated with this telnetsession
     */
    public final void setDataObject(Object object) {
        data = object;
    }
}
