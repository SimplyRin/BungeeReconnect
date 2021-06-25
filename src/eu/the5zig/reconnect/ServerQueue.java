package eu.the5zig.reconnect;

import java.util.concurrent.TimeUnit;

public interface ServerQueue {

    public Holder queue(long timeout, TimeUnit unit);

    public long getCurrentHoldTime();

    public long getCurrentWaitTime();

}