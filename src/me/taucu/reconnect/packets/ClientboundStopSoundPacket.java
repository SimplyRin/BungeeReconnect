package me.taucu.reconnect.packets;

import dev.simplix.protocolize.api.PacketDirection;
import dev.simplix.protocolize.api.Protocol;
import dev.simplix.protocolize.api.Protocolize;
import dev.simplix.protocolize.api.SoundCategory;
import dev.simplix.protocolize.api.mapping.ProtocolIdMapping;
import dev.simplix.protocolize.api.packet.AbstractPacket;
import dev.simplix.protocolize.api.util.ProtocolUtil;
import io.netty.buffer.ByteBuf;
import me.taucu.reconnect.util.RangedProtocolMapping;
import net.md_5.bungee.protocol.BadPacketException;

import java.util.ArrayList;

public class ClientboundStopSoundPacket extends AbstractPacket {

    private String name;
    private SoundCategory category;

    public ClientboundStopSoundPacket() {}

    public ClientboundStopSoundPacket(String name, SoundCategory category) {
        this.name = name;
        this.category = category;
    }

    @Override
    public void read(ByteBuf buf, PacketDirection dir, int ver) {
        int flag = buf.readByte();
        if ((flag & 1) > 0) {
            int categoryId = ProtocolUtil.readVarInt(buf);
            SoundCategory[] categories = SoundCategory.values();
            if (categoryId >= 0 && categoryId < categories.length) {
                category = SoundCategory.values()[categoryId];
            } else {
                throw new BadPacketException("categoryId is out of range: " + categoryId);
            }
        } else {
            category = null;
        }

        if ((flag & 2) > 0) {
            name = ProtocolUtil.readString(buf);
        } else {
            name = null;
        }
    }

    @Override
    public void write(ByteBuf buf, PacketDirection dir, int ver) {
        if (category == null) {
            if (name == null) {
                buf.writeByte(0);
            } else {
                buf.writeByte(2);
                ProtocolUtil.writeString(buf, name);
            }
        } else if (name == null) {
            buf.writeByte(1);
            ProtocolUtil.writeVarInt(buf, category.ordinal());
        } else {
            buf.writeByte(3);
            ProtocolUtil.writeVarInt(buf, category.ordinal());
            ProtocolUtil.writeString(buf, name);
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SoundCategory getCategory() {
        return category;
    }

    public void setCategory(SoundCategory category) {
        this.category = category;
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
