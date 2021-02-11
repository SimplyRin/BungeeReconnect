package eu.the5zig.reconnect;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class ServerQueue {
	
	private final QueueManager parent;	
	
	private final ReentrantLock lock = new ReentrantLock(true);
	
	private volatile AtomicBoolean wait = new AtomicBoolean(false);
	
	private volatile long lastTime = 0;	
	
	public ServerQueue(QueueManager parent) {
		this.parent = parent;
	}
	
	public synchronized Holder queue(long timeout, TimeUnit unit) {				
		try {
			if (lock.tryLock(timeout, unit)) {
				long ctime = System.nanoTime();
				
				long sleepTime = Math.max(parent.instance().getNanosBetweenConnects() - (ctime - lastTime), 1);
				//lastTime = ctime + sleepTime;
				
				try {
					Thread.sleep(TimeUnit.NANOSECONDS.toMillis(sleepTime));
				} catch (InterruptedException e) {
					//lastTime = System.nanoTime();
					e.printStackTrace();
				}
				
				ctime = System.nanoTime();
				long ftime = parent.instance().getConnctFinalizationNanos();
				
				while (wait.get()) {
					synchronized (wait) {
						wait.wait(Math.max(TimeUnit.NANOSECONDS.toMillis(ftime - (System.nanoTime() - ctime)), 1));	
					}
					if (lastTime + ftime < System.nanoTime()) {
						wait = new AtomicBoolean(true);
						break;
					}
				}
				
				wait = new AtomicBoolean(true);
				return new Holder(this, wait);
			}
		} catch (InterruptedException e) {
			return new Holder(this, new AtomicBoolean());
		} finally {
			lastTime = System.nanoTime();
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
