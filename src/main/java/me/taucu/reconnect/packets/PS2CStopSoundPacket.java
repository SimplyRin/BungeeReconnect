package me.taucu.reconnect.packets;

import java.util.Arrays;
import java.util.List;

import dev.simplix.protocolize.api.PacketDirection;
import dev.simplix.protocolize.api.SoundCategory;
import dev.simplix.protocolize.api.mapping.AbstractProtocolMapping;
import dev.simplix.protocolize.api.mapping.ProtocolIdMapping;
import dev.simplix.protocolize.api.packet.AbstractPacket;
import dev.simplix.protocolize.api.util.ProtocolUtil;
import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.Setter;
import net.md_5.bungee.protocol.BadPacketException;

@Setter
@Getter
public class PS2CStopSoundPacket extends AbstractPacket {

    public static final SoundCategory[] SOUND_CATEGORIES = SoundCategory.values();

    private String name;
    private SoundCategory category;

    public PS2CStopSoundPacket() {}

    public PS2CStopSoundPacket(String name, SoundCategory category) {
        this.name = name;
        this.category = category;
    }

    @Override
    public void read(ByteBuf buf, PacketDirection dir, int ver) {
        int flag = buf.readByte();
        if ((flag & 1) > 0) {
            int categoryId = ProtocolUtil.readVarInt(buf);
            if (categoryId >= 0 && categoryId < SOUND_CATEGORIES.length) {
                category = SOUND_CATEGORIES[categoryId];
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

    public static final List<ProtocolIdMapping> MAPPINGS = Arrays.asList(
            AbstractProtocolMapping.rangedIdMapping(343, 344, 0x49)
            , AbstractProtocolMapping.rangedIdMapping(345, 351, 0x4A)
            , AbstractProtocolMapping.rangedIdMapping(352, 388, 0x4B)
            , AbstractProtocolMapping.rangedIdMapping(389, 450, 0x4C)
            , AbstractProtocolMapping.rangedIdMapping(451, 470, 0x4D)
            , AbstractProtocolMapping.rangedIdMapping(471, 498, 0x52)
            , AbstractProtocolMapping.rangedIdMapping(550, 719, 0x53)
            , AbstractProtocolMapping.rangedIdMapping(721, 754, 0x52)
            , AbstractProtocolMapping.rangedIdMapping(755, 756, 0x5D)
            , AbstractProtocolMapping.rangedIdMapping(757, 759, 0x5E)
            , AbstractProtocolMapping.rangedIdMapping(760, 760, 0x61)
            , AbstractProtocolMapping.rangedIdMapping(761, 761, 0x5F)
            , AbstractProtocolMapping.rangedIdMapping(762, 763, 0x63) // 1.20   - 1.20.1
            , AbstractProtocolMapping.rangedIdMapping(764, 764, 0x66) // 1.20.2
            , AbstractProtocolMapping.rangedIdMapping(765, 765, 0x68) // 1.20.3 - 1.20.4
            , AbstractProtocolMapping.rangedIdMapping(766, 766, 0x68) // 1.20.5 - 1.20.6
            , AbstractProtocolMapping.rangedIdMapping(767, 767, 0x68) // 1.21   - 1.21.1
            , AbstractProtocolMapping.rangedIdMapping(768, 768, 0x6F) // 1.21.2 - 1.21.3
            , AbstractProtocolMapping.rangedIdMapping(769, 769, 0x6F) // 1.21.4
    );

    public static boolean isSupportedVersion(int protocolVersion) {
        return MAPPINGS.stream().anyMatch(m -> m.inRange(protocolVersion));
    }

}
