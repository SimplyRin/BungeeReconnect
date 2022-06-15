package me.taucu.reconnect.packets;

import dev.simplix.protocolize.api.PacketDirection;
import dev.simplix.protocolize.api.SoundCategory;
import dev.simplix.protocolize.api.mapping.AbstractProtocolMapping;
import dev.simplix.protocolize.api.mapping.ProtocolIdMapping;
import dev.simplix.protocolize.api.packet.AbstractPacket;
import dev.simplix.protocolize.api.util.ProtocolUtil;
import io.netty.buffer.ByteBuf;
import net.md_5.bungee.protocol.BadPacketException;

import java.util.Arrays;
import java.util.List;

public class ClientboundStopSoundPacket extends AbstractPacket {

    static final SoundCategory[] CATEGORIES = SoundCategory.values();

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
            if (categoryId >= 0 && categoryId < CATEGORIES.length) {
                category = CATEGORIES[categoryId];
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

    public static final List<ProtocolIdMapping> MAPPINGS = Arrays.asList(
            AbstractProtocolMapping.rangedIdMapping(343, 344, 0x49),
            AbstractProtocolMapping.rangedIdMapping(345, 351, 0x4A),
            AbstractProtocolMapping.rangedIdMapping(352, 388, 0x4B),
            AbstractProtocolMapping.rangedIdMapping(389, 450, 0x4C),
            AbstractProtocolMapping.rangedIdMapping(451, 470, 0x4D),
            AbstractProtocolMapping.rangedIdMapping(471, 498, 0x52),
            AbstractProtocolMapping.rangedIdMapping(550, 719, 0x53),
            AbstractProtocolMapping.rangedIdMapping(721, 754, 0x52),
            AbstractProtocolMapping.rangedIdMapping(755, 756, 0x5D),
            AbstractProtocolMapping.rangedIdMapping(757, 759, 0x5E)
    );

}
