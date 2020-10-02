package eu.the5zig.reconnect;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.the5zig.reconnect.util.concurrent.MyReentrantLock;

public class ServerQueue {
	
	private final QueueManager parent;	
	
	private final MyReentrantLock lock = new MyReentrantLock(true);
	
	private volatile AtomicBoolean wait = new AtomicBoolean(false);
	
	private volatile long lastTime = System.nanoTime();	
	
	public ServerQueue(QueueManager parent) {
		this.parent = parent;
	}
	
	public synchronized Holder queue(long timeout, TimeUnit unit) {				
		try {
			if (lock.tryLock(timeout, unit)) {
				long ctime = System.nanoTime();
				
				long sleepTime = Math.max(parent.instance().getNanosBetweenConnects() - (ctime - lastTime), 0);
				
				// we add sleepTime rather than putting this after Thread#sleep because we 
				// cannot trust that it will not be interrupted for some reason™
				lastTime = ctime + sleepTime;
				
				try {
					Thread.sleep(TimeUnit.NANOSECONDS.toMillis(sleepTime));
				} catch (InterruptedException e) {
					lastTime = System.nanoTime();
					e.printStackTrace();
				}
				
				ctime = System.nanoTime();
				long ftime = parent.instance().getConnctFinalizationNanos();
				
				while (wait.get()) {
					synchronized (wait) {
						wait.wait(TimeUnit.NANOSECONDS.toMillis(Math.abs(ftime - (System.nanoTime() - ctime))));	
					}
					if (lastTime + ftime < System.nanoTime()) {
						wait = new AtomicBoolean(true);
						break;
					}
				}
				wait.set(true);
				return new Holder(this, wait);
			}
		} catch (InterruptedException e) {
			return new Holder(this, new AtomicBoolean());
		} finally {
			lock.unlock();
		}		
		return null;
	}
	
	protected long getCurrentHoldTime() {
		return Math.max(-1, (lastTime + parent.instance().getConnctFinalizationNanos()) - System.nanoTime());
	}
	
	protected long getCurrentWaitTime() {
		return Math.max(-1, (lastTime + parent.instance().getNanosBetweenConnects() - System.nanoTime()));
	}
	
}
