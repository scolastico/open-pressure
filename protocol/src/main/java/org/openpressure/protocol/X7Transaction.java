package org.openpressure.protocol;

/** Checks WLP1COM transport readiness before starting an X7 read session. */
public final class X7Transaction {
    private final byte[] nonce;
    private boolean ready;

    public X7Transaction(byte[] nonce) {
        if(nonce==null || nonce.length!=4) throw new IllegalArgumentException("X7 transaction requires a four-byte nonce");
        this.nonce=nonce.clone();
    }

    /** Send on the system characteristic using WRITE_TYPE_NO_RESPONSE. */
    public byte[] request() {
        byte[] request=new byte[20];
        request[0]=0x11;
        System.arraycopy(nonce,0,request,1,nonce.length);
        return request;
    }

    /** Ignores absent/unrelated notifications and rejects invalid transaction responses. */
    public boolean accept(byte[] reply) {
        if(reply==null || reply.length==0 || (reply[0]&255)!=0x91) return false;
        // SYS replies may fill the negotiated notification size (244 bytes on the observed X7).
        // The command's 20-byte TX length is not a limit on its padded response.
        if(reply.length<7) throw new IllegalArgumentException("Invalid X7 transaction response length");
        if(reply[1]!=0 || reply[6]!=0) throw new IllegalArgumentException("X7 transaction was rejected");
        for(int i=0;i<nonce.length;i++) {
            if(reply[i+2]!=nonce[i]) throw new IllegalArgumentException("X7 transaction nonce does not match");
        }
        // The original transaction guard ignores reserved bytes after the final flag.
        ready=true;
        return true;
    }

    public boolean ready() { return ready; }
}
