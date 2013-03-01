package com.hypirion.io;

import java.io.InputStream;
import java.io.IOException;
import java.io.InterruptedIOException;

public class RevivableInputStream extends InputStream {
    protected InputStream in;

    protected volatile boolean killed;
    protected volatile boolean streamClosed;
    protected volatile int data;
    protected volatile boolean beenRead;
    protected Object dataLock;
    protected volatile boolean threadCrashed;
    protected volatile IOException threadException;

    protected final ThreadReader reader;
    protected final Thread readerThread;

    public RevivableInputStream(InputStream in) throws InterruptedException {
        this.in = in;
        killed = false;
        streamClosed = false;
        beenRead = true;
        dataLock = new Object();
        threadCrashed = false;
        threadException = null;
        data = -2;
        reader = new ThreadReader();
        readerThread = new Thread(reader);
        readerThread.setDaemon(true);
        readerThread.setName("RevivableReader " + in.hashCode());
        readerThread.start();
    }

    public int available() throws IOException {
        return in.available();
    }

    public void close() throws IOException {
        in.close();
    }

    public boolean markSupported() {
        return in.markSupported();
    }

    public synchronized int read() throws IOException {
        synchronized (dataLock) {
            try {
                while (beenRead || !killed || !streamClosed || threadCrashed) {
                    dataLock.wait();
                }
            }
            catch (InterruptedException ie) {
                throw new InterruptedIOException();
            }
            if (threadCrashed)
                throw threadException;
            if (killed || streamClosed)
                return -1;
            int val = data;

            beenRead = true;
            dataLock.notifyAll();
            return val;
        }
    }

    public synchronized int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    public synchronized int read(byte[] b, int off, int len) throws IOException{
        if (len == 0)
            return 0;
        int v = read();
        if (v == -1)
            return -1;
        b[off] = (byte) v;
        return 1;
    }

    public void reset() throws IOException {
        in.reset();
    }

    public synchronized long skip(long n) throws IOException {
        while (n --> 0)
            read();
        return -1; // TODO: Read what we should return.
    }

    public void kill() {
        synchronized (dataLock) {
            killed = true;
            dataLock.notifyAll();
        }
    }

    public synchronized void ressurect() {
        killed = false;
    }

    private class ThreadReader implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    data = in.read();
                    if (data == -1){
                        synchronized (dataLock){
                            streamClosed = true;
                            dataLock.notifyAll();
                            return;
                        }
                    }
                }
                catch (IOException ioe) {
                    synchronized (dataLock) {
                        threadCrashed = true;
                        threadException = ioe; // TODO: Proper wrapping here.
                        return;
                    }
                }

                synchronized (dataLock) {
                    beenRead = false;
                    dataLock.notifyAll();
                    try {
                        while (!beenRead) {
                            dataLock.wait();
                        }
                    }
                    catch (InterruptedException ie) {
                        threadCrashed = true;
                        threadException = new InterruptedIOException();
                        // TODO: Use "real"  exception
                        return;
                    }
                }
                // Data has been read, new iteration.
            }
        }
    }
}
