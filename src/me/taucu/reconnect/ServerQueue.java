package me.taucu.reconnect;

import java.util.concurrent.TimeUnit;

public interface ServerQueue {
    
    Holder queue(long timeout, TimeUnit unit);
    
    long getCurrentHoldTime();
    
    long getCurrentWaitTime();
    
}