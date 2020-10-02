package eu.the5zig.reconnect;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import net.md_5.bungee.api.config.ServerInfo;

public class QueueManager {
	
	private final Reconnect instance;
	
	/**
	 * A HashMap containing all connection queued servers
	 */
	private Map<ServerInfo, ServerQueue> serverQueueMap = new HashMap<ServerInfo, ServerQueue>();
	
	protected QueueManager(Reconnect instance) {
		this.instance = instance;
	}
	
	public synchronized Holder queue(ServerInfo info, long queueTimeout, TimeUnit queueTimeoutUnit) {
		ServerQueue queue = getServerQueue(info);
		
		return queue.queue(queueTimeout, queueTimeoutUnit);		
	}
	
	public ServerQueue getServerQueue(ServerInfo info) {
		synchronized (serverQueueMap) {
			ServerQueue queue = serverQueueMap.get(info);
			
			if (queue == null) {
				queue = new ServerQueue(this);
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
