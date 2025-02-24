package me.taucu.reconnect.net;

import org.joor.Reflect;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import me.taucu.reconnect.Reconnector;
import net.md_5.bungee.BungeeServerInfo;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.netty.HandlerBoss;
import net.md_5.bungee.netty.PipelineUtils;
import net.md_5.bungee.protocol.MinecraftDecoder;
import net.md_5.bungee.protocol.MinecraftEncoder;
import net.md_5.bungee.protocol.Protocol;
import net.md_5.bungee.protocol.channel.ChannelAcceptor;

public class ReconnectChannelInitializer extends ChannelInitializer<Channel> {
    
    private final Reconnector connector;
    
    private final ProxyServer bungee;
    private final UserConnection user;
    private final BungeeServerInfo target;
    
    public ReconnectChannelInitializer(Reconnector connector, ProxyServer bungee, UserConnection user, BungeeServerInfo target) {
        this.connector = connector;
        this.bungee = bungee;
        this.user = user;
        this.target = target;
    }
    
    @Override
    protected void initChannel(Channel ch) throws Exception {
    	ChannelAcceptor BASE = Reflect.onClass(PipelineUtils.class).field("BASE").get();
        BASE.accept(ch);
        ch.pipeline().addAfter(PipelineUtils.FRAME_DECODER, PipelineUtils.PACKET_DECODER,
                new MinecraftDecoder(Protocol.HANDSHAKE, false, user.getPendingConnection().getVersion()));
        ch.pipeline().addAfter(PipelineUtils.FRAME_PREPENDER_AND_COMPRESS, PipelineUtils.PACKET_ENCODER,
                new MinecraftEncoder(Protocol.HANDSHAKE, false, user.getPendingConnection().getVersion()));
        ch.pipeline().get(HandlerBoss.class).setHandler(new ReconnectServerConnector(connector, bungee, user, target));
    }
    
}
