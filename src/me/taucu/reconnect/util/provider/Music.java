package me.taucu.reconnect.util.provider;

import dev.simplix.protocolize.api.SoundCategory;
import dev.simplix.protocolize.api.player.ProtocolizePlayer;
import dev.simplix.protocolize.data.Sound;

public class Music {

    final Sound music;
    final SoundCategory category;
    final float volume;
    final float pitch;

    public Music(Sound music, SoundCategory category, float volume, float pitch) {
        this.music = music;
        this.category = category;
        this.volume = volume;
        this.pitch = pitch;
    }

    public void play(ProtocolizePlayer player) {
        player.playSound(music, category, volume, pitch);
    }

}
