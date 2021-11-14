package org.crumbleworks.forge.eightfiftyfour;

/**
 * Thrown during the setup-phase of the {@link TelnetServer}. Indicates issues
 * with the configuration, etc.
 *
 * @author Michael Stocker
 * @since 0.1.0
 */
public final class ServerSetupException extends RuntimeException {

    private static final long serialVersionUID = -154979358320730546L;

    protected ServerSetupException(Throwable cause) {
        super(cause);
    }

    protected ServerSetupException(String cause) {
        super(cause);
    }
}
