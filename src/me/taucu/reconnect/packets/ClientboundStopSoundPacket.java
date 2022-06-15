package me.taucu.reconnect.packets;

import dev.simplix.protocolize.api.PacketDirection;
import dev.simplix.protocolize.api.Protocol;
import dev.simplix.protocolize.api.Protocolize;
import dev.simplix.protocolize.api.mapping.ProtocolIdMapping;
import dev.simplix.protocolize.api.packet.AbstractPacket;
import io.netty.buffer.ByteBuf;
import me.taucu.reconnect.util.RangedProtocolMapping;

import java.util.ArrayList;

public class ClientboundStopSoundPacket extends AbstractPacket {

    @Override
    public void read(ByteBuf byteBuf, PacketDirection packetDirection, int i) {
        // we don't do that here
    }

    @Override
    public void write(ByteBuf buf, PacketDirection packetDirection, int i) {
        // stop all sounds
        buf.writeByte(0);
    }

    public static void register() {
        ArrayList<ProtocolIdMapping> idMap = new ArrayList<>();
        idMap.add(new RangedProtocolMapping(0x49, 343, 344));
        idMap.add(new RangedProtocolMapping(0x4A, 345, 351));
        idMap.add(new RangedProtocolMapping(0x4B, 352, 388));
        idMap.add(new RangedProtocolMapping(0x4C, 389, 450));
        idMap.add(new RangedProtocolMapping(0x4D, 451, 470));
        idMap.add(new RangedProtocolMapping(0x52, 471, 498));
        idMap.add(new RangedProtocolMapping(0x53, 550, 719));
        idMap.add(new RangedProtocolMapping(0x52, 721, 754));
        idMap.add(new RangedProtocolMapping(0x5D, 755, 756));
        idMap.add(new RangedProtocolMapping(0x5E, 757, 759));

        Protocolize.protocolRegistration().registerPacket(idMap, Protocol.PLAY, PacketDirection.CLIENTBOUND, ClientboundStopSoundPacket.class);
    }

}
