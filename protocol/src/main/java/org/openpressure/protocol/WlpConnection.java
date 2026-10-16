package org.openpressure.protocol;

/** One WLP access session per connection: END acknowledgment is terminal for the X7. */
public final class WlpConnection {
    private WlpReadSession read;
    private boolean finished,closed;

    public WlpReadSession begin(int start,int length,int blockSize) {
        if(read!=null || finished || closed) throw new IllegalArgumentException("Reconnect before starting another WLP session");
        read=new WlpReadSession(start,length,blockSize);
        return read;
    }

    public void finishTransfer() {
        if(closed || read==null || !read.complete()) throw new IllegalArgumentException("WLP session has no validated END acknowledgment");
        finished=true;
    }

    public boolean shutdownExpected() { return finished; }

    /** A link closure cannot turn a partial read or an unacknowledged END into success. */
    public boolean close() { closed=true; return finished; }
}
