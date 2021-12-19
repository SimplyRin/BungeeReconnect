package me.taucu.reconnect.net;

import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelPipeline;
import me.taucu.reconnect.Reconnect;
import me.taucu.reconnect.Reconnecter;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.PacketWrapper;
import net.md_5.bungee.protocol.packet.Kick;

public class DownstreamInboundHandler extends ChannelHandlerAdapter implements ChannelInboundHandler {
    
    public static final String NAME = "me.taucu.reconnect:downstream-inbound-handler";
    
    private final Reconnect instance;
    private final UserConnection ucon;
    
    private final ServerConnection server;
    
    private volatile boolean wasLegitimatelyKicked = false;
    
    public static void attachHandlerTo(UserConnection ucon, Reconnect instance) {
        ChannelPipeline pipeline = ucon.getServer().getCh().getHandle().pipeline();
        if (pipeline.get(NAME) != null) {
            pipeline.remove(NAME);
        }
        pipeline.addBefore("inbound-boss", NAME, new DownstreamInboundHandler(ucon, instance));
    }
    
    public DownstreamInboundHandler(UserConnection ucon, Reconnect instance) {
        this.instance = instance;
        this.ucon = ucon;
        
        this.server = ucon.getServer();
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
        instance.debug(this, "HANDLE_CHANNEL_INACTIVE");
        Reconnecter reconnecter = instance.getReconnecterFor(ucon.getUniqueId());
        if (reconnecter != null && reconnecter.getServer().getInfo().equals(server.getInfo())) {
            instance.debug(this, "  handling, reconnect if applicable");
            if (instance.reconnectIfApplicable(ucon, server)) {
                server.setObsolete(true);
                // return so fireChannelInactive isn't called
                return;
            }
        }
        // handle normally only if they have been legitimately kicked, they have switched servers or they disconnected
        if (wasLegitimatelyKicked || !instance.isUserOnline(ucon) || !ucon.getServer().getInfo().equals(server.getInfo())) {
            instance.debug(this, "  handling normally, wasLegitimatelyKicked=" + wasLegitimatelyKicked + ", onlineCheck=" + !instance.isUserOnline(ucon) + ", serverCheck=" + !ucon.getServer().getInfo().equals(server.getInfo()));
            ctx.fireChannelInactive();
        }
    }
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object obj) throws Exception {
        if (obj instanceof PacketWrapper) {
            PacketWrapper wrapper = (PacketWrapper) obj;
            boolean propagate = true;
            try {
                DefinedPacket packet = wrapper.packet;
                if (packet instanceof Kick) {
                    Kick kick = (Kick) packet;
                    instance.debug(this, "HANDLE_KICK for " + ucon.getName() + " on server " + server.getInfo().getName() + " with message \"" + kick.getMessage() + "\"");
                    
                    // assume true for now
                    boolean legitimateKick = true;
                    
                    // check if the server is ignored to save time
                    if (!instance.isIgnoredServer(server.getInfo())) {
                        
                        // needs to be parsed like that...
                        String kickMessage = ChatColor.stripColor(BaseComponent.toLegacyText(ComponentSerializer.parse(kick.getMessage())));
                        
                        instance.debug(this, "kick message stripped for " + ucon.getName() + " on server " + server.getInfo().getName() + " : \"" + kickMessage + "\"");
                        
                        // check if kickMessage is a restart message
                        if (instance.isReconnectKick(kickMessage)) {
                            // reconnect if applicable & check if we will
                            if (instance.reconnectIfApplicable(ucon, server)) {
                                // don't let HandlerBoss get its grubby little hands on this
                                server.setObsolete(true);
                                // don't propagate this to the next handler
                                propagate = false;
                                legitimateKick = false;
                            } else {
                                instance.debug(this, "not handling because reconnectIfApplicable returned false");
                            }
                        } else {
                            instance.debug(this, "not handling because the kick message does not match the configured message.");
                        }
                    } else {
                        instance.debug(this, "not handling because it's an ignored server.");
                    }
                    
                    wasLegitimatelyKicked = legitimateKick;
                }
            } finally {
                if (propagate) {
                    ctx.fireChannelRead(obj);
                } else {
                    wrapper.trySingleRelease();
                }
            }
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
            if (instance.reconnectIfApplicable(ucon, server)) {
                instance.debug(this, "  handling, reconnecting");
                server.setObsolete(true);
                // return so fireExceptionCaught isn't called
                return;
            }
        }
        ctx.fireExceptionCaught(yeet);
    }
    
}
