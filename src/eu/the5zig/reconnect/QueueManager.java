package eu.the5zig.reconnect;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

import eu.the5zig.reconnect.api.PlayerQueueEvent;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.config.ServerInfo;

public class QueueManager {
    
    private final Reconnect instance;
    
    /**
     * A WeakHashMap containing all connection queued servers We use a weak hash map
     * in the event that a plugin meddles with the current servers at runtime
     */
    private final Map<ServerInfo, ServerQueue> serverQueueMap = new WeakHashMap<ServerInfo, ServerQueue>();
    
    protected QueueManager(Reconnect instance) {
        this.instance = instance;
    }
    
    public synchronized Holder queue(ServerInfo info, UserConnection con, long queueTimeout,
            TimeUnit queueTimeoutUnit) {
        ServerQueue queue = getServerQueue(info);
        
        PlayerQueueEvent e = new PlayerQueueEvent(info, queue, con, queueTimeout, queueTimeoutUnit);
        instance.getProxy().getPluginManager().callEvent(e);
        
        if (e.isCancelled()) {
            return null;
        }
        
        return e.getQueue().queue(queueTimeout, queueTimeoutUnit);
    }
    
    public ServerQueue getServerQueue(ServerInfo info) {
        synchronized (serverQueueMap) {
            ServerQueue queue = serverQueueMap.get(info);
            
            if (queue == null) {
                queue = new StandardServerQueue(this);
                serverQueueMap.put(info, queue);
            }
            
            return queue;
        }
    }
    
    protected Reconnect instance() {
        return instance;
    }
    
    public void removeQueueFor(ServerInfo info) {
        synchronized (serverQueueMap) {
            serverQueueMap.remove(info);
        }
    }
    
}
