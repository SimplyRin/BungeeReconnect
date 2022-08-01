package me.taucu.reconnect;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class StandardServerQueue implements ServerQueue {
    
    private final QueueManager parent;
    
    private final ReentrantLock queueLock = new ReentrantLock(true);
    
    private volatile AtomicBoolean conWait = new AtomicBoolean(false);
    
    private volatile long lastTime = 0;
    
    public StandardServerQueue(QueueManager parent) {
        this.parent = parent;
    }
    
    @Override
    public synchronized Holder queue(long timeout, TimeUnit unit) {
        
        try {
            if (queueLock.tryLock(timeout, unit)) {
                long ctime = System.nanoTime();
                
                long sleepTime = Math.max(parent.instance().getNanosBetweenConnects() - (ctime - lastTime), 1);
                
                try {
                    Thread.sleep(TimeUnit.NANOSECONDS.toMillis(sleepTime));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                
                ctime = System.nanoTime();
                long ftime = parent.instance().getConnectFinalizationNanos();
                
                while (conWait.get()) {
                    synchronized (conWait) {
                        conWait.wait(Math.max(TimeUnit.NANOSECONDS.toMillis(ftime - (System.nanoTime() - ctime)), 1));
                    }
                    if (lastTime + ftime < System.nanoTime()) {
                        conWait = new AtomicBoolean(true);
                        break;
                    }
                }
                
                conWait = new AtomicBoolean(true);
                return new Holder(this, conWait);
            }
        } catch (InterruptedException e) {
            return new Holder(this, new AtomicBoolean());
        } finally {
            lastTime = System.nanoTime();
            queueLock.unlock();
        }
        return null;
    }
    
    @Override
    public long getCurrentHoldTime() {
        return Math.max(-1, (lastTime + parent.instance().getConnectFinalizationNanos()) - System.nanoTime());
    }
    
    @Override
    public long getCurrentWaitTime() {
        return Math.max(-1, (lastTime + parent.instance().getNanosBetweenConnects() - System.nanoTime()));
    }
    
}
