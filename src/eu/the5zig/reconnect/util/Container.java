package eu.the5zig.reconnect.util;

public class Container<T> {
    
    private volatile T t;
    
    public Container(T t) {
        this.t = t;
    }
    
    public T get() {
        return t;
    }
    
    public void set(T t) {
        this.t = t;
    }
    
}
