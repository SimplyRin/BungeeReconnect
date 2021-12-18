package eu.the5zig.reconnect.net;

import eu.the5zig.reconnect.Reconnect;
import eu.the5zig.reconnect.Reconnecter;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import net.md_5.bungee.connection.CancelSendSignal;
import net.md_5.bungee.connection.DownstreamBridge;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.protocol.packet.Kick;
import net.md_5.bungee.protocol.packet.PlayerListItem;
import net.md_5.bungee.protocol.packet.PlayerListItem.Action;
import net.md_5.bungee.protocol.packet.PlayerListItem.Item;

/**
 * Our own implementation of the BungeeCord DownstreamBridge.<br>
 * Inside here, all packets going from the Minecraft Server to the Minecraft
 * Client are being handled.
 */
public class ReconnectBridge extends DownstreamBridge {
    
    private final Reconnect instance;
    private final UserConnection user;
    private final ServerConnection server;
    
    public ReconnectBridge(Reconnect instance, ProxyServer bungee, UserConnection user, ServerConnection server) {
        super(bungee, user, server);
        this.instance = instance;
        this.user = user;
        this.server = server;
    }
    
    @Override
    public void exception(Throwable t) throws Exception {
        instance.debug(this, "HANDLE_EXCEPTION");
        // Usually, BungeeCord would reconnect the Player to the fallback server or kick him if no
        // Fallback Server is available.
        // when an Exception between the BungeeCord and the Minecraft Server
        // occurs. We override this Method so that we can try to reconnect the client instead.
        
        // Fire ServerReconnectEvent and give plugins the possibility to cancel server
        // reconnecting.
        if (!instance.isIgnoredServer(server.getInfo()) && instance.fireServerReconnectEvent(user, server)) {
            // setObsolete so that DownstreamBridge.disconnected(ChannelWrapper) won't be called.
            server.setObsolete(true);
            
            instance.reconnectIfOnline(user, server);
            return;
        } else {
            instance.debug(this, "not handling because it's an ignored server or reconnect event has been cancelled");
            super.exception(t);
        }
    }
    
    @Override
    public void handle(Kick kick) throws Exception {
        instance.debug(this, "HANDLE_KICK for " + user.getName() + " on server " + server.getInfo().getName()
                + " with message \"" + kick.getMessage() + "\"");
        // This method is called whenever a Kick-Packet is sent from the Minecraft Server to the Minecraft Client.
        
        // check if the server is ignored
        if (!instance.isIgnoredServer(server.getInfo())) {
            
            // needs to be parsed like that...
            String kickMessage = ChatColor.stripColor(BaseComponent.toLegacyText(ComponentSerializer.parse(kick.getMessage())));
            
            instance.debug(this, "kick message stripped for " + user.getName() + " on server "
                    + server.getInfo().getName() + " : \"" + kickMessage + "\"");
            
            // Check if kickMessage is a restart message
            if (instance.isReconnectKick(kickMessage)) {
                // As always, we fire a ServerReconnectEvent and give plugins the possibility to
                // cancel server reconnecting.
                if (instance.fireServerReconnectEvent(user, server)) {
                    // Otherwise, reconnect the User if he is still online.
                    server.setObsolete(true);
                    instance.reconnectIfOnline(user, server);
                    // Throw Exception so that the Packet won't be sent to the Minecraft Client.
                    throw CancelSendSignal.INSTANCE;
                } else {
                    // Invoke default behavior if event has been cancelled and disconnect the
                    // player.
                    instance.debug(this, "not handling because a plugin cancelled the reconnect event");
                }
            } else {
                instance.debug(this, "not handling because the kick message does not match the configured message.");
            }
        } else {
            instance.debug(this, "not handling because it's an ignored server.");
        } // otherwise handle it normally
        super.handle(kick);
    }
    
    @Override
    public void handle(PlayerListItem playerListItem) throws Exception {
        instance.debug("HANDLE_PLAYERADD");
        if (playerListItem.getAction() == Action.ADD_PLAYER && playerListItem.getItems() != null) {
            for (Item item : playerListItem.getItems()) {
                if (item != null && user.getUniqueId().equals(item.getUuid())) {
                    Reconnecter reconnecter = instance.getReconnecterFor(user.getUniqueId());
                    if (reconnecter != null && reconnecter.getServer().getInfo().equals(server.getInfo())) {
                        reconnecter.setJoinFlag(true);
                    }
                    break;
                }
            }
            
        }
        super.handle(playerListItem);
    }
    
    @Override
    public void disconnected(ChannelWrapper channel) throws Exception {
        instance.debug("DISCONNECTED");
        Reconnecter reconnecter = instance.getReconnecterFor(user.getUniqueId());
        if (reconnecter != null) {
            reconnecter.setJoinFlag(false);
        } else {
            instance.debug("  NOT_CURRENTLY_RECONNECTING");
            super.disconnected(channel);
        }
    }
    
}
