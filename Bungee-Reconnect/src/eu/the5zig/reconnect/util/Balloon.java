package eu.the5zig.reconnect.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Balloon {
	
	private AtomicBoolean lock = new AtomicBoolean(true);
	
	public void pop() {
		synchronized (lock) {
			lock.set(false);
			lock.notifyAll();
		}
	}
	
	public void await() throws InterruptedException {
		synchronized (lock) {
			if (lock.get()) {
				lock.wait();	
			}
		}
	}
	
	public void await(long time, TimeUnit unit) throws InterruptedException {
		synchronized (lock) {
			if (lock.get()) {
				lock.wait(unit.toMillis(time));
			}
		}
	}

}
