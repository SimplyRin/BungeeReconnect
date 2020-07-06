package eu.the5zig.reconnect;

import com.google.common.base.Strings;

import eu.the5zig.reconnect.net.BasicChannelInitializer;
import eu.the5zig.reconnect.util.scheduler.Sched;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.util.internal.PlatformDependent;
import net.md_5.bungee.BungeeServerInfo;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.event.ServerConnectEvent.Reason;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.netty.PipelineUtils;
import net.md_5.bungee.protocol.packet.KeepAlive;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class Reconnecter {

	private static final TextComponent EMPTY = new TextComponent("");
	private static final Random rand = new Random();

	private final Reconnect instance;
	private final ProxyServer bungee;
	private final UserConnection user;
	private final ServerConnection server;
	private final BungeeServerInfo target;
	
	// The time start() was called/class constructed.
	private long startTime = System.currentTimeMillis();
	
	// The updates task itself, nonull once started.
	private ScheduledTask updatesTask = null;
	// Whether or not to send updates (keepalive/titles etc)
	private boolean updates = false;

	// How many tries this reconnecter did.
	private int tries = 0;
	
	// If this is cancelled
	private boolean cancelled = false;
	// If this is running
	private boolean running = false;
	
	// The current future, this is not null if the last future was a success.	
	private ChannelFuture currentFuture = null;
	
	private final Runnable run = new Runnable() {
		@Override
		public void run() {
			if (cancelled) {
				return;
			}
			// Remove pending reconnect because we will retry later on
			user.getPendingConnects().remove(target);
			
			// Only retry to reconnect the user if he is still online and hasn't been moved to another server, do this:
			if (instance.isUserOnline(user) && Objects.equals(user.getServer(), server)) {
				
				// Ensure the client doesn't timeout
				user.unsafe().sendPacket(new KeepAlive(rand.nextLong()));
				
				// Increment current reconnect tries
				tries++;
				
				// if the tries are above the max config'ed tries: kick the player back to fallback or from the proxy if fallback doesn't work disconnect them.
				if (tries > instance.getMaxReconnectTries()) {
					// first, Cancel this reconnecter as it is not needed anymore.
					instance.cancelReconnecterFor(user.getUniqueId());
					// Proceed with plan B :(
					failReconnect();
					
				} else {// If the reconnect tries are within limits, do this instead:
					
					/*
					 * If the current channel future set in tryReconnect is
					 * not null, and the channel is inactive/closed close it and retry the reconnect
					 * after setting it to null for the next cycle
					 */
					if (currentFuture != null) {
						// If the chanel is active (open and ready/active right now) there is no need to close it or attempt another reconnect
						// Instead wait for it to time out
						if (currentFuture.channel().isActive()) {
							retry();
							return;
						}
						currentFuture.channel().close();
						currentFuture = null;
					}
					// Attempt a reconnect
					tryReconnect();
				}
			} else { //Otherwise cancel this reconnecter as it ain't needed no more
				instance.cancelReconnecterFor(user.getUniqueId());
			}
		}
	};

	/**
	 * 
	 * @param instance The instance of reconnect
	 * @param bungee The proxyserver
	 * @param user The user to reconnect
	 * @param server The server connection the user will try to reconnect to
	 */
	public Reconnecter(Reconnect instance, ProxyServer bungee, UserConnection user, ServerConnection server) {
		this.instance = instance;
		this.bungee = bungee;
		this.user = user;
		this.server = server;
		this.target = server.getInfo();
	}
	
	/**
	 * Only when called this reconnecter will attempt to function
	 * Can only be called once.
	 * 
	 * Once the reconnecter is finished/cancelled This method will not work!
	 * Create a new instance instead
	 */
	public void start() {
		if (running && !cancelled) {
			return;
		}
		running = true;
		startTime = System.currentTimeMillis();
		// Create runnable if not existing; send tiles and action bar updates every 200 Miliseconds (as well as keepAlive Packets)
		startSendingUpdates();
		// invoke the "run" runnable after the delay before trying
		Sched.scheduleAsync(instance, run, instance.getDelayBeforeTrying(), TimeUnit.MILLISECONDS);
	}
	
	private void retry() {
		// invoke the "run" runnable after the reconnect milis
		Sched.scheduleAsync(instance, run, instance.getReconnectMillis(), TimeUnit.MILLISECONDS);
	}

	/**
	 * Tries to reconnect the User to the specified Server. In case that fails, this method will be executed again
	 * after a short timeout.
	 */
	private void tryReconnect() {

		try {
			// If we are already connecting to a server, cancel the reconnect task.
			if (user.getPendingConnects().contains(target)) {
				instance.getLogger().warning("\"" + user.getName() + "\" already connecting to \"" + target.getName() + "\"");
				return;
			}
			
    		// Establish connection to the server.
			ChannelInitializer<Channel> initializer = new BasicChannelInitializer(bungee, user, target);
			ChannelFutureListener listener = new ChannelFutureListener() {
				@Override
				public void operationComplete(ChannelFuture future) throws Exception {
					if (future.isSuccess()) {
						currentFuture = future;
					} else {
						future.channel().close();
					}
				}
			};
			// Create a new Netty Bootstrap that contains the ChannelInitializer and the ChannelFutureListener.
			Bootstrap bootstrap = new Bootstrap().channel(PipelineUtils.getChannel(target.getAddress())).group(server.getCh().getHandle().eventLoop()).handler(initializer).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, instance.getReconnectTimeout()).remoteAddress(target.getAddress());

			// Windows is bugged, multi homed users will just have to live with random connecting IPs
			if (user.getPendingConnection().getListener().isSetLocalAddress() && !PlatformDependent.isWindows()) {
				bootstrap.localAddress(((InetSocketAddress) user.getPendingConnection().getListener().getSocketAddress()).getHostString(), 0);
			}
			
			bootstrap.connect().addListener(listener);	
			// Call next retry to check the isConnected state etc irrelevant of the outcome of the future.
			retry();
		} catch (Exception e) { //If an exception occurs on the channel, such as a readTimeoutException, or any other channel exception, retry the reconnect
			instance.getLogger().warning("exeception in reconnect task for \"" + user.getName() + "\" for server \"" + target.getName() + "\" : \"" + e.getMessage() + "\"");
			retry();
		}
	}
	
	
	/**
	 * If called and the reconnecter is running this will mimic standard proxy behavior
	 * and will kick the player back to fall back (if exists) if not kick them to the title screen
	 */
	public void failReconnect() {
		cancel();

		@SuppressWarnings("deprecation")
		ServerInfo def = bungee.getServerInfo(user.getPendingConnection().getListener().getFallbackServer());
		if (target != def) {
			// If the fallback-server is not the same server we tried to reconnect to, send the user to that one instead.
			server.setObsolete(true);
			user.connectNow(def, Reason.PLUGIN);
			user.sendMessage(bungee.getTranslation("server_went_down"));
			
			
			// Send fancy title if it's enabled in config, otherwise reset the connecting title.
			if (!instance.getFailedTitle().isEmpty())
				user.sendTitle(createFailedTitle());
			else
				user.sendTitle(ProxyServer.getInstance().createTitle().reset());

			// Send fancy action bar message if it's enabled in config, otherwise reset the connecting action bar message.
			if (!instance.getFailedActionBar().isEmpty())
				sendFailedActionBar(user);
			else
				user.sendMessage(ChatMessageType.ACTION_BAR, EMPTY);
		} else {
			// Otherwise, disconnect the user with a "Lost Connection"-message.
			user.disconnect(bungee.getTranslation("lost_connection"));
		}
	}

	private void startSendingUpdates() {
		if (updates == true) {//Only allow invocation once
			return;
		}
		updates = true;
		startSendingUpdatesAbs();
	}
	
	private void startSendingUpdatesAbs() {
		updatesTask = ProxyServer.getInstance().getScheduler().schedule(instance, () -> {
			if (user.isConnected() && updates && !cancelled) {
				// Send keep alive packet so user will not disconnect if the server feels like not sending any for whatever reason;
				user.unsafe().sendPacket(new KeepAlive(rand.nextLong()));
				if (currentFuture == null) {
					// Send fancy Title
					if (!instance.getReconnectingTitle().isEmpty()) {
						createReconnectTitle().send(user);
					}

					// Send fancy Action Bar Message
					if (!instance.getReconnectingActionBar().isEmpty()) {
						sendReconnectActionBar(user);
					}
		
				} else {					
					// Send fancy Title
					if (!instance.getConnectingTitle().isEmpty()) {
						createConnectingTitle().send(user);
					}

					// Send fancy Action Bar Message
					if (!instance.getConnectingActionBar().isEmpty()) {
						sendConnectActionBar(user);
					}
				}
				//Loop
				startSendingUpdatesAbs();
			} else {
				stopSendingUpdates();
			}
		}, 50L, TimeUnit.MILLISECONDS);
	}
	
	private void stopSendingUpdates() {
		if (updatesTask != null) {
			updates = false;
			updatesTask.cancel();
			updatesTask = null;
		}
	}

	/**
	 * Sends an Action Bar Message containing the reconnect-text to the player.
	 */
	private void sendReconnectActionBar(UserConnection user) {
		user.sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(instance.getReconnectingActionBar().replace("{%dots%}", instance.getDots(startTime))));
	}
	
	/**
	 * Sends an Action Bar Message containing the connect-text to the player.
	 */
	private void sendConnectActionBar(UserConnection user) {
		user.sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(instance.getConnectingActionBar().replace("{%dots%}", instance.getDots(startTime))));
	}

	/**
	 * Sends an Action Bar Message containing the failed-text to the player.
	 */
	private void sendFailedActionBar(final UserConnection user) {
		user.sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(instance.getFailedActionBar()));

		// Send an empty action bar message after 5 seconds to make it disappear again.
		bungee.getScheduler().schedule(instance, new Runnable() {
			@Override
			public void run() {
				user.sendMessage(ChatMessageType.ACTION_BAR, EMPTY);
			}
		}, 5L, TimeUnit.SECONDS);
	}
	
	/**
	 * Creates a Title containing the reconnect-text.
	 *
	 * @return a Title that can be send to the player.
	 */
	private Title createReconnectTitle() {
		Title title = ProxyServer.getInstance().createTitle();
		title.title(new TextComponent(instance.getReconnectingTitle().replace("{%dots%}", instance.getDots(startTime))));
		if (!instance.getReconnectingSubtitle().isEmpty()) {
			title.subTitle(new TextComponent(instance.getReconnectingSubtitle().replace("{%dots%}", instance.getDots(startTime))));
		}
		// Stay at least as long as the longest possible connect-time can be.
		title.stay(120);
		title.fadeIn(0);
		title.fadeOut(0);

		return title;
	}

	/**
	 * Creates a Title containing the connecting-text.
	 *
	 * @return a Title that can be send to the player.
	 */
	private Title createConnectingTitle() {
		Title title = ProxyServer.getInstance().createTitle();
		title.title(new TextComponent(instance.getConnectingTitle().replace("{%dots%}", instance.getDots(startTime))));
		if (!instance.getConnectingSubtitle().isEmpty()) {
			title.subTitle(new TextComponent(instance.getConnectingSubtitle().replace("{%dots%}", instance.getDots(startTime))));
		}
		title.stay(120);
		title.fadeIn(0);
		title.fadeOut(10);

		return title;
	}
	
	/**
	 * Created a Title containing the failed-text.
	 *
	 * @return a Title that can be send to the player.
	 */
	private Title createFailedTitle() {
		Title title = ProxyServer.getInstance().createTitle();
		title.title(new TextComponent(instance.getFailedTitle()));
		if (!instance.getFailedSubtitle().isEmpty()) {
			title.subTitle(new TextComponent(instance.getFailedSubtitle().replace("{%dots%}", instance.getDots(startTime))));
		}		
		title.stay(120);
		title.fadeIn(0);
		title.fadeOut(10);

		return title;
	}

	/**
	 * Cancels this reconnecter
	 * Note: if the player is already connecting to the server this will not stop it.
	 */
	public void cancel() {
		cancelled = true;
		running = false;
		updates = false;
		if (instance.isUserOnline(user)) {
			if (!Strings.isNullOrEmpty(instance.getReconnectingTitle()) || !Strings.isNullOrEmpty(instance.getConnectingTitle())) {
				// For some reason, we have to reset and clear the title, so it completely disappears -> BungeeCord bug?
				bungee.createTitle().reset().clear().send(user);
			}
			if (!Strings.isNullOrEmpty(instance.getConnectingActionBar())) {
				user.sendMessage(ChatMessageType.ACTION_BAR, EMPTY);
			}
		}
	}

}
