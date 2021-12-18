package eu.the5zig.reconnect.net;

import eu.the5zig.reconnect.Reconnect;
import eu.the5zig.reconnect.Reconnecter;
import net.md_5.bungee.BungeeServerInfo;
import net.md_5.bungee.ServerConnector;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.connection.CancelSendSignal;
import net.md_5.bungee.protocol.packet.Kick;

/**
 * This is my implementation of {@link ServerConnector} used by {@link ReconnectChannelInitializer} when reconnecting someone.
 * <br>
 * This is responsible for handling kicks and other things that might disconnect the user while reconnecting.
 * @author Tau
 */
public class ReconnectServerConnector extends ServerConnector {
    
    final Reconnect instance;
    final Reconnecter connecter;
    
    public ReconnectServerConnector(Reconnecter connecter, ProxyServer bungee, UserConnection user, BungeeServerInfo target) {
        super(bungee, user, target);
        this.connecter = connecter;
        this.instance = connecter.getReconnect();
    }
    
    @Override
    public void exception(Throwable t) throws Exception {
        instance.debug("HANDLE_EXCEPTION");
        if (connecter.isCancelled()) {
            instance.debug("  connecter is cancelled, handle normally");
            super.exception(t);
        } else {
            throw CancelSendSignal.INSTANCE;
        }
    }
    
    @Override
    public void handle(Kick kick) throws Exception {
        instance.debug("HANDLE_KICK");
        if (connecter.isCancelled()) {
            instance.debug("  connecter is cancelled, handle normally");
            super.handle(kick);
        } else {
            throw CancelSendSignal.INSTANCE;
        }
    }
    
}
