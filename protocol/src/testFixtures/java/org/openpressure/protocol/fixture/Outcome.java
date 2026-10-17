package org.openpressure.protocol.fixture;

import java.util.HexFormat;

/** Result of replaying one client against one fixture. */
public final class Outcome {
    public enum Kind { MATCHES, DIVERGED, REJECTED, UNSUPPORTED }
    public final Kind kind;
    public final int step,line;
    public final byte[] expected,actual;
    public final String detail;

    Outcome(Kind kind,int step,int line,byte[] expected,byte[] actual,String detail) {
        this.kind=kind; this.step=step; this.line=line; this.expected=expected; this.actual=actual; this.detail=detail;
    }
    public static Outcome matches(int steps) { return new Outcome(Kind.MATCHES,steps,0,null,null,"followed all "+steps+" steps"); }
    public static Outcome rejected(int step,String detail) { return new Outcome(Kind.REJECTED,step,0,null,null,detail); }
    public static Outcome unsupported(String detail) { return new Outcome(Kind.UNSUPPORTED,0,0,null,null,detail); }
    static Outcome diverged(int step,int line,byte[] expected,byte[] actual,String detail) {
        return new Outcome(Kind.DIVERGED,step,line,expected,actual,detail);
    }

    public String report() {
        StringBuilder out=new StringBuilder(kind.name().toLowerCase());
        if(kind!=Kind.MATCHES && kind!=Kind.UNSUPPORTED) out.append(" at step ").append(step).append(line>0?" (fixture line "+line+")":"");
        out.append(": ").append(detail);
        if(expected!=null) out.append("\n  expected ").append(HexFormat.of().formatHex(expected)).append("  ").append(WlpDescribe.describe(expected));
        if(actual!=null) out.append("\n  actual   ").append(HexFormat.of().formatHex(actual)).append("  ").append(WlpDescribe.describe(actual));
        return out.toString();
    }
    @Override public String toString() { return report(); }
}
