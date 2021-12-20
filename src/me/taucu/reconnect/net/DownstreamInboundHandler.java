package me.taucu.reconnect.net;

import java.util.logging.Level;

import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelPipeline;
import me.taucu.reconnect.Reconnect;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.PacketWrapper;
import net.md_5.bungee.protocol.packet.Kick;

public class DownstreamInboundHandler extends ChannelHandlerAdapter implements ChannelInboundHandler {
    
    public static final String NAME = "me.taucu.reconnect:downstream-inbound-handler";
    
    private final Reconnect instance;
    private final UserConnection ucon;
    
    private final ServerConnection server;
    
    private final ChannelWrapper ch;
    
    private volatile boolean legitimateKick = false;
    private volatile boolean startedReconnecting = false;
    
    public static void attachHandlerTo(UserConnection ucon, Reconnect instance) {
        ChannelPipeline pipeline = ucon.getServer().getCh().getHandle().pipeline();
        attachHandlerTo(pipeline, ucon, instance);
    }
    
    public static void attachHandlerTo(ChannelPipeline pipeline, UserConnection ucon, Reconnect instance) {
        if (pipeline.get(NAME) != null) {
            pipeline.remove(NAME);
        }
        pipeline.addBefore("inbound-boss", NAME, new DownstreamInboundHandler(ucon, instance));
    }
    
    public DownstreamInboundHandler(UserConnection ucon, Reconnect instance) {
        this.instance = instance;
        this.ucon = ucon;
        
        this.server = ucon.getServer();
        this.ch = ucon.getServer().getCh();
        instance.debug(this, "<init>");
    }
    
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelRegistered();
    }
    
    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelUnregistered();
    }
    
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelActive();
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        instance.debug(this, "HANDLE_CHANNEL_INACTIVE sameServer=" + (ucon.getServer() == server));
        if (startedReconnecting) {
            instance.debug(this, "already reconnecting");
            server.setObsolete(true);
            ch.markClosed();
            return;
        } else {
            if (ucon.getServer() == server && !legitimateKick) {
                instance.debug(this, "handling, reconnect if applicable");
                if (instance.reconnectIfApplicable(ucon, server)) {
                    startedReconnecting = true;
                    server.setObsolete(true);
                    ch.markClosed();
                    log("lost connection");
                    // return so fireChannelInactive isn't called
                    return;
                }
            }
            ctx.fireChannelInactive();
        }
    }
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object obj) throws Exception {
        if (obj instanceof PacketWrapper) {
            boolean fireNextRead = true;
            PacketWrapper wrapper = (PacketWrapper) obj;
            try {
                DefinedPacket packet = wrapper.packet;
                if (packet instanceof Kick) {
                    Kick kick = (Kick) packet;
                    instance.debug(this, "HANDLE_KICK for " + ucon.getName() + " on server " + server.getInfo().getName() + " with message \"" + kick.getMessage() + "\"");
                    
                    boolean legitimageKick = true;
                    
                    if (!instance.isIgnoredServer(server.getInfo())) {
                        
                        // needs to be parsed like that...
                        String kickMessage = ChatColor.stripColor(BaseComponent.toLegacyText(ComponentSerializer.parse(kick.getMessage())));
                        
                        instance.debug(this, "kick message stripped for " + ucon.getName() + " on server " + server.getInfo().getName() + " : \"" + kickMessage + "\"");
                        
                        // check if kickMessage is a restart message
                        if (instance.isReconnectKick(kickMessage)) {
                            log("matched kick message: " + kickMessage);
                            // reconnect if applicable & check if we will
                            if (instance.reconnectIfApplicable(ucon, server)) {
                                // don't propagate this to the next handler
                                fireNextRead = false;
                                legitimageKick = false;
                                startedReconnecting = true;
                                server.setObsolete(true);
                            } else {
                                instance.debug(this, "not handling because reconnectIfApplicable returned false");
                            }
                        } else {
                            instance.debug(this, "not handling because the kick message does not match the configured message.");
                        }
                    } else {
                        instance.debug(this, "not handling because it's an ignored server.");
                    }
                    
                    this.legitimateKick = legitimageKick;
                    
                }
            } finally {
                if (fireNextRead) {
                    ctx.fireChannelRead(obj);
                } else {
                    wrapper.trySingleRelease();
                }
            }
        } else {
            ctx.fireChannelRead(obj);
        }
    }
    
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelReadComplete();
    }
    
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object obj) throws Exception {
        ctx.fireUserEventTriggered(obj);
    }
    
    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        ctx.fireChannelWritabilityChanged();
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable yeet) throws Exception {
        if (ctx.channel().isActive()) {
            instance.debug(this, "HANDLE_EXCEPTION", yeet);
            if (startedReconnecting) {
                instance.debug(this, "already reconnecting");
            } else if (ucon.getServer() == server && instance.reconnectIfApplicable(ucon, server)) {
                instance.debug(this, "reconnecting");
                startedReconnecting = true;
                server.setObsolete(true);
                log("Exception caught: " + (yeet == null ? "null" : yeet.getLocalizedMessage()));
                // return so fireExceptionCaught isn't called
                return;
            }
        }
        
        if (!startedReconnecting) {
            ctx.fireExceptionCaught(yeet);
        }
    }
    
    public void log(String msg) {
        ProxyServer.getInstance().getLogger().info("[" + ucon.getName() + "] <-> ReconnectDownstreamHandler <-> [" + server.getInfo().getName() + "] " + msg);
    }
    
    public void log(String msg, Throwable t) {
        log(Level.WARNING, msg, t);
    }
    
    public void log(Level level, String msg, Throwable t) {
        ProxyServer.getInstance().getLogger().log(level, "[" + ucon.getName() + "] <-> ReconnectDownstreamHandler <-> [" + server.getInfo().getName() + "] " + msg, t);
    }
    
}
