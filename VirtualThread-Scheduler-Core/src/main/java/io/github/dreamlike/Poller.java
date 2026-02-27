package io.github.dreamlike;


import java.io.IOException;
import java.util.function.BooleanSupplier;


public interface Poller {
    /**
     * Parks the current thread until bytes are read a byte array. This method is
     * overridden by poller implementations that support this operation.
     */
    int implRead(int fdVal, byte[] b, int off, int len, long nanos,
                 BooleanSupplier isOpen) throws IOException;

    /**
     * Parks the current thread until bytes are written from a byte array. This
     * method is overridden by poller implementations that support this operation.
     */
    int implWrite(int fdVal, byte[] b, int off, int len,
                  BooleanSupplier isOpen) throws IOException;

    default void implStartPoll(int fdVal) {

    }

    default void implStopPoll(int fdVal, boolean polled) {

    }

    default  int poll(int timeout) {
        throw new UnsupportedOperationException("you can ignore this!");
    }

    default void close() {

    }

}
