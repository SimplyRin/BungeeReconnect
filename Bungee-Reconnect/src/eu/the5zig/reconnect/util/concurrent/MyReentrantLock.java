package eu.the5zig.reconnect.util.concurrent;

import java.util.Collection;
import java.util.concurrent.locks.ReentrantLock;

public class MyReentrantLock extends ReentrantLock {

	private static final long serialVersionUID = -3431008474182976811L;
	
	public MyReentrantLock(boolean b) {
		super(b);
	}

	public Collection<Thread> getQueuedThreads1() {
		return this.getQueuedThreads();
	}

}
