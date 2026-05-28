package io.netty.channel.uring;

//todo 移除改为常量
public class NativePeek {
    public static final int POLLIN = Native.POLLIN;
    public static final int POLLOUT = Native.POLLOUT;

    public static final byte IORING_OP_POLL_ADD = Native.IORING_OP_POLL_ADD;

}
