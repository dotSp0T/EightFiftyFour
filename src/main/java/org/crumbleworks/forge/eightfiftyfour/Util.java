package org.crumbleworks.forge.eightfiftyfour;

/**
 * @author Michael Stocker
 * @since 0.1.0
 */
public final class Util {

    private Util() {}

    public final static <T> T assignOrThrow(T value, String msg) {
        if(value == null) {
            throw new IllegalArgumentException(msg);
        }

        return value;
    }
}
