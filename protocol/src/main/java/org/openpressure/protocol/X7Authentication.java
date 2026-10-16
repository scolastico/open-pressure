package org.openpressure.protocol;

/** Polls cuff authorization after WLP1COM_PAIR encryption is established. */
public final class X7Authentication {
    public enum Result { IGNORED, WAITING, COMPLETE, REJECTED }
    private boolean complete;

    public byte[] command() {
        byte[] command=new byte[20];
        command[0]=0x20;
        command[1]=1;
        return command;
    }

    public Result accept(byte[] reply) {
        if(reply==null || reply.length==0 || (reply[0]&255)!=0xa0) return Result.IGNORED;
        if(reply.length<2) throw new IllegalArgumentException("Truncated X7 authorization response");
        if(reply[1]!=1) return Result.IGNORED;
        if(reply.length<4) throw new IllegalArgumentException("Truncated X7 authorization response");
        if(reply[2]!=0) throw new IllegalArgumentException("X7 authorization command failed");
        Result result;
        switch(reply[3]&255) {
            case 0: result=Result.WAITING; break;
            case 1: result=Result.COMPLETE; break;
            case 2: result=Result.REJECTED; break;
            default: throw new IllegalArgumentException("Unknown X7 authorization state");
        }
        complete=result==Result.COMPLETE;
        return result;
    }

    public boolean complete() { return complete; }
}
