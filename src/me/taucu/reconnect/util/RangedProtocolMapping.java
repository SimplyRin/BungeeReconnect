package me.taucu.reconnect.util;

import dev.simplix.protocolize.api.mapping.ProtocolIdMapping;

public class RangedProtocolMapping implements ProtocolIdMapping {

    private int id;
    private int start;
    private int end;

    public RangedProtocolMapping(int id, int start, int end) {
        this.id = id;
        this.start = start;
        this.end = end;
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public int protocolRangeStart() {
        return start;
    }

    @Override
    public int protocolRangeEnd() {
        return end;
    }

    @Override
    public String toString() {
        return "RangedProtocolMapping{" +
                "protocolRangeStart=" + start +
                ", protocolRangeEnd=" + end +
                ", packetId=0x" + Integer.toHexString(id) +
                '}';
    }

}
