package me.taucu.reconnect.util.provider;

import dev.simplix.protocolize.api.Protocolize;
import dev.simplix.protocolize.api.player.ProtocolizePlayer;
import dev.simplix.protocolize.api.providers.ProtocolizePlayerProvider;
import lombok.Getter;
import me.taucu.reconnect.packets.ClientboundStopSoundPacket;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class MusicProvider {

    final ProtocolizePlayerProvider provider = Protocolize.playerProvider();
    @Getter final List<Music> musics;

    public MusicProvider(Collection<Music> musics) {
        this.musics = new ArrayList<>(musics);
    }

    public void playMusic(ProxiedPlayer player) {
        if (musics.size() > 0) {
            ProtocolizePlayer protoPlayer = provider.player(player.getUniqueId());
            protoPlayer.sendPacket(new ClientboundStopSoundPacket());
            musics.get(ThreadLocalRandom.current().nextInt(musics.size()))
                    .play(protoPlayer);
        }
    }


}
