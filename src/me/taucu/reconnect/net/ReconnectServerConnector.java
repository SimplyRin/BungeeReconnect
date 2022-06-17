package me.taucu.reconnect.net;

import me.taucu.reconnect.Reconnect;
import me.taucu.reconnect.Reconnector;
import net.md_5.bungee.BungeeServerInfo;
import net.md_5.bungee.ServerConnector;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.protocol.packet.Kick;
import net.md_5.bungee.protocol.packet.LoginSuccess;

/**
 * This is my implementation of {@link ServerConnector} used by {@link ReconnectChannelInitializer} when reconnecting someone.
 * <br>
 * This is responsible for handling kicks and other things that might disconnect the user while reconnecting.
 * Additionally sets the {@link Reconnector#setJoinFlag(boolean)} when login success occurs.
 * @author Tau
 */
public class ReconnectServerConnector extends ServerConnector {
    
    final Reconnect instance;
    final Reconnector connector;
    
    final UserConnection ucon;
    final BungeeServerInfo target;
    
    public ReconnectServerConnector(Reconnector connector, ProxyServer bungee, UserConnection user, BungeeServerInfo target) {
        super(bungee, user, target);
        this.connector = connector;
        this.instance = connector.getReconnect();
        this.ucon = user;
        this.target = target;
    }
    
    @Override
    public void exception(Throwable t) throws Exception {
        instance.debug(this, "HANDLE_EXCEPTION");
        if (connector.isCancelled()) {
            instance.debug(this, "  connector is cancelled, handle normally");
            super.exception(t);
        }
    }
    
    @Override
    public void handle(Kick kick) throws Exception {
        instance.debug(this, "HANDLE_KICK");
        if (connector.isCancelled()) {
            instance.debug(this, "  connector is cancelled, handle normally");
            super.handle(kick);
        }
    }
    
    @Override
    public void handle(LoginSuccess loginSuccess) throws Exception {
        instance.debug(this, "HANDLE_LOGIN_SUCCESS");
        connector.setJoinFlag(true);
        super.handle(loginSuccess);
    }
    
    @Override
    public String toString() {
        return "[" + this.ucon.getName() + "] <-> ReconnectServerConnector [" + this.target.getName() + "]";
    }
    
}
