package com.attc.opencode;

/** JNI bridge to the forkpty() helper. */
public final class Pty {

    static {
        System.loadLibrary("pty");
    }

    private Pty() {
    }

    /** @return {masterFd, pid} or null on failure. */
    public static native int[] nativeOpen(String[] argv, String[] envp,
                                          String cwd, int rows, int cols);

    public static native int nativeRead(int fd, byte[] buf);

    public static native int nativeWrite(int fd, byte[] buf, int off, int len);

    public static native int nativeResize(int fd, int rows, int cols);

    /** @return exit code, -1 while running, -2 if already reaped. */
    public static native int nativeWaitPid(int pid);

    public static native void nativeClose(int fd);
}
