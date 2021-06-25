package eu.the5zig.reconnect.util;

import java.net.SocketAddress;

import com.google.common.base.Preconditions;

import io.netty.channel.Channel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.unix.DomainSocketAddress;

public class MyPipelineUtils {

    public static boolean epoll = Epoll.isAvailable();

    public static Class<? extends Channel> getChannel(SocketAddress address) {
	if (address instanceof DomainSocketAddress) {
	    Preconditions.checkState(epoll, "Epoll required to have UNIX sockets");
	    return EpollDomainSocketChannel.class;
	} else {
	    return epoll ? EpollSocketChannel.class : NioSocketChannel.class;
	}
    }

}
