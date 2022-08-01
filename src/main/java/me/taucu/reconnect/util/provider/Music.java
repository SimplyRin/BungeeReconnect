package me.taucu.reconnect.util.provider;

import dev.simplix.protocolize.api.SoundCategory;
import dev.simplix.protocolize.api.player.ProtocolizePlayer;
import dev.simplix.protocolize.data.Sound;
import lombok.Data;

@Data
public class Music {

    final Sound music;
    final SoundCategory category;
    final float volume;
    final float pitch;

    public void play(ProtocolizePlayer player) {
        player.playSound(music, category, volume, pitch);
    }

}
