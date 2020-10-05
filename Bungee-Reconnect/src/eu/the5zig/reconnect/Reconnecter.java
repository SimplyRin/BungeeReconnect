package eu.the5zig.reconnect;

import eu.the5zig.reconnect.net.BasicChannelInitializer;
import eu.the5zig.reconnect.util.scheduler.Sched;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import net.md_5.bungee.BungeeServerInfo;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.netty.PipelineUtils;
import net.md_5.bungee.protocol.packet.KeepAlive;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class Reconnecter {

	private static final TextComponent EMPTY = new TextComponent("");
	private static final Random rand = new Random();
	
	private final Reconnect instance;
	private final ProxyServer bungee;
	private final UserConnection user;
	private final ServerConnection server;
	private final BungeeServerInfo target;

	// The time start() was called/object constructed.
	private long startTime = System.nanoTime();
	
	// last time a try was attempted
	private long lastFutureTime = 0;
	
	// The updates task itself, nonull once started.
	private ScheduledTask updatesTask = null;
	// Whether or not to send updates (keepalive/titles etc)
	private boolean updates = false;
	private final long updateRate;
	private final int stayTime;
	
	// If this is cancelled
	private boolean cancelled = false;
	// If this is running
	private boolean running = false;
	
	// The current future. this is not null if the last future was a success.	
	private ChannelFuture channelFuture = null;
	
	// The current holder if any
	private Holder holder = null;
	
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
		this.updateRate = instance.getTitleUpdateRate();
		this.stayTime = (int) Math.ceil(this.updateRate/50D) + 20;
	}
	
	private final Runnable run = new Runnable() {
		@Override
		public void run() {
			if (cancelled) {
				return;
			}
			final ChannelFuture future = channelFuture;
			
			// Only retry to reconnect the user if they are still online and hasn't been moved to another server etc, do this:
			if (statusCheck()) {
				// check if timeout has expired
				if (hasTimedOut()) {
					// Proceed with plan B :(
					failReconnect();
					return;
				} else {
					
					/*
					 * Check if the future is null
					 */
					if (future != null) {
						
						// check if the channel has been canceled or has completed but failed
						if (!future.isCancelled() && !(future.isDone() && !(future.isSuccess() && future.channel().isActive())) && lastFutureTime + TimeUnit.MILLISECONDS.toNanos(instance.getReconnectTimeout()) > System.nanoTime()) {
							retry();
							return;	
						}
						
						try {
							dropHolder();
							future.cancel(true);
							future.channel().close();
						} catch (Exception e) {
							if (!(e instanceof InterruptedException || e instanceof IOException)) {
								instance.getLogger().log(Level.WARNING, "Unexpected exception while closing inactive channel", e);
							}
						}
					}
					
					// Attempt a reconnect
					tryReconnect();
					return;
				}
			} //Otherwise cancel this reconnecter as it ain't needed no more
			cancel();
		}
	};
	
	/**
	 * Checks if the user is reconnected or has moved to a different server/went offline
	 * @return true if the user is online has not switched servers and is not reconnected yet
	 */
	public boolean statusCheck() {
		return isOnline() && (isSameServer() || user.getDimension() == null);
	}
	
	/**
	 * Checks of the user is connected to the same server as the one they lost connection to.
	 * @return if the user is on the same server connection
	 */
	public boolean isSameServer() {
		return Objects.equals(user.getServer(), server);
	}
	
	
	/**
	 * checks of the user is online
	 * @return if the user is online on this proxy
	 */
	public boolean isOnline() {
		return instance.isUserOnline(user);
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
		startTime = System.nanoTime();
		
		// Create runnable if not existing; send tiles and action bar updates (as well as keepAlive Packets)
		startSendingUpdates();
		// invoke the "run" runnable after the delay before trying
		Sched.scheduleAsync(instance, run, instance.getDelayBeforeTrying(), TimeUnit.MILLISECONDS);
	}
	
	/**
	 * Called when a retry should occer
	 */
	private void retry() {
		// invoke the "run" runnable after 50 msec
		Sched.scheduleAsync(instance, run, 50, TimeUnit.MILLISECONDS);
	}

	/**
	 * Abstracted logic that is called every time a new connection attempt should be made
	 */
	private void tryReconnect() {

		try {
			
    		// Create channel initializer.
			ChannelInitializer<Channel> initializer = new BasicChannelInitializer(bungee, user, target);		
			
			// Create a new Netty Bootstrap that contains the ChannelInitializer and the ChannelFutureListener.
			Bootstrap bootstrap = new Bootstrap().channel(PipelineUtils.getChannel(target.getAddress())).group(server.getCh().getHandle().eventLoop()).handler(initializer).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, instance.getReconnectTimeout()).remoteAddress(target.getAddress());
			
			// Set the server connection that they are currently on to obsolete.
			user.getServer().setObsolete(true);
			
			// Remove pending reconnect because we are about to reconnect them.
			user.getPendingConnects().remove(target);
			
			// create entry in queue and wait if needed
			holder = instance.waitForConnect(target, getRemainingTime(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS);
			
			if (!statusCheck()) {
				cancel();
				return;
			}
			
			// connect
			ChannelFuture future = bootstrap.connect();
			
			try {
				// wait for the future to finish or fail for no longer then reconnect timeout
				future.get(instance.getReconnectTimeout(), TimeUnit.MILLISECONDS);
				channelFuture = future;
				lastFutureTime = System.nanoTime();
			} catch (Exception e) { // we ignore exceptions here as many will be thrown as some attempts fail
				closeChannel(future);
				dropHolder();
			}
			
		} catch (Exception e) { // if any other exception occurs here log it.
			instance.getLogger().log(Level.WARNING, "unexpected exception thrown in reconnect task for \"" + user.getName() + "\" for server \"" + target.getName() + "\" : \"" + e.getMessage() + "\"", e);
		}
		// Call next retry to check the connection state etc irrelevant of the outcome of the future.
		retry();
	}
	
	/**
	 * Drops the currently held lock
	 */
	public void dropHolder() {
		final Holder h = holder;
		if (h != null) {
			h.unlock();
			holder = null;
		}
	}
	
	/**
	 * Used to cancel and close a nullable future
	 * @param future The nullable future to close
	 * @throws Exception when and if an exception occurs
	 */
	public void closeChannel(ChannelFuture future) throws Exception {
		if (future != null) {
			future.channel().close();
			future.cancel(true);
		}
	}
	
	/**
	 * Called to cancel, close and remove the channelFuture from this reconnecter.
	 */
	public void removeChannel() {
		try {
			closeChannel(channelFuture);
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			channelFuture = null;
		}
	}
	
	/**
	 * Identical to removeChannel but will only do so if the future is incomplete / inactive
	 */
	public void removeChannelIfIncomplete() {
		final ChannelFuture future = this.channelFuture;
		if (future != null && (!future.isSuccess() || !future.channel().isActive())) {
			removeChannel();
		}
	}
	
	
	/**
	 * If called and the reconnecter is running this will mimic standard proxy behavior
	 * and will kick the player back to fall back (if exists) if not kick them to the title screen
	 */
	public void failReconnect() {
		removeChannel();
		cancel();
		
		List<ServerInfo> fallbacks = instance.getFallbackServersFor(user);
		fallbacks.remove(target);
		
		// If the fallback-server is not the same server we tried to reconnect to, send the user to that one instead.
		server.setObsolete(true);
		
		// Send fancy title if it's enabled in config, otherwise reset the connecting title.
		if (!instance.getFailedTitle().isEmpty()) {
			user.sendTitle(createFailedTitle());
		} else {
			user.sendTitle(ProxyServer.getInstance().createTitle().reset());
		}
		// Send fancy action bar message if it's enabled in config, otherwise reset the connecting action bar message.
		if (!instance.getFailedActionBar().isEmpty()) {
			sendFailedActionBar(user);
		} else {
			user.sendMessage(ChatMessageType.ACTION_BAR, EMPTY);
		}
		
		instance.fallback(user, fallbacks.iterator(), new Callable<Object>() {
			@Override
			public Object call() throws Exception {
				user.disconnect(instance.getFailedKickMessage());
				return null;
			}
		});
	}

	/**
	 * Used to enable sending of titles, action bars and keep alives.
	 * see also: {@link Reconnecter#stopSendingUpdates()}
	 */
	private void startSendingUpdates() {
		if (updates == false) {//Only allow invocation once
			updates = true;
			update();
		}
	}
	
	/**
	 * Method used to queue the next update. Should not be called directly as it will
	 * first wait for the set update rate before calling {@link Reconnecter#update()}
	 */
	private void queueUpdate() {
		updatesTask = ProxyServer.getInstance().getScheduler().schedule(instance, () -> {
			update();
		}, updateRate, TimeUnit.MILLISECONDS);
	}
	
	/**
	 * This is normally called by queueUpdate() and is the method that
	 * sends titles, action bars and keep alives.
	 * see also: {@link Reconnecter#queueUpdate()}
	 */
	private void update() {
		if (statusCheck() && updates && !cancelled) {
			// Send keep alive packet so user will not timeout.
			user.unsafe().sendPacket(new KeepAlive(rand.nextLong()));
			if (channelFuture == null) {
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
			queueUpdate();
		} else {
			stopSendingUpdates();
		}
	}
	
	/**
	 * Used to disable updates from being sent. 
	 * see also: {@link Reconnecter#startSendingUpdates()}
	 */
	private void stopSendingUpdates() {
		if (updatesTask != null) {
			updates = false;
			updatesTask.cancel();
		}
	}
	
	/**
	 * gets the time left before {@link Reconnect#getMaxReconnectTime()} expires
	 * @returns The remaining time in nanoseconds
	 */
	public long getRemainingTime(TimeUnit unit) {
		return unit.convert(Math.max(instance.getMaxReconnectNanos() - (System.nanoTime() - startTime), 0), TimeUnit.NANOSECONDS);
	}
	
	/**
	 * @returns true if {@link Reconnect#getMaxReconnectTime()} has expired
	 * @returns false otherwise
	 */
	public boolean hasTimedOut() {
		return getRemainingTime(TimeUnit.NANOSECONDS) < 1;
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
	}
	
	/**
	 * Creates a Title containing the reconnect-text.
	 *
	 * @return a Title that can be send to the player.
	 */
	private Title createReconnectTitle() {
		Title title = ProxyServer.getInstance().createTitle();
		title.title(new TextComponent(instance.getReconnectingTitle().replace("{%dots%}", instance.getDots(startTime))));
		title.subTitle(new TextComponent(instance.getReconnectingSubtitle().replace("{%dots%}", instance.getDots(startTime))));
		title.stay(stayTime);
		title.fadeIn(0);
		title.fadeOut(1);

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
		title.subTitle(new TextComponent(instance.getConnectingSubtitle().replace("{%dots%}", instance.getDots(startTime))));
		title.stay(stayTime);
		title.fadeIn(0);
		title.fadeOut(1);

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
		title.subTitle(new TextComponent(instance.getFailedSubtitle().replace("{%dots%}", instance.getDots(startTime))));
		title.stay(stayTime);
		title.fadeIn(0);
		title.fadeOut(1);

		return title;
	}
	
	/**
	 * Clears all titles and action bar messages
	 */
	public void clearAnimations() {
		if (instance.isUserOnline(user)) {
			// clear action bar
			user.sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
			// clear title
			Title title = ProxyServer.getInstance().createTitle();
			title.title(new TextComponent(""));
			title.subTitle(new TextComponent(""));
			title.send(user);	
		}
	}

	/**
	 * Cancels this reconnecter
	 * Note: if the player is already connecting to the server this will not stop it.
	 */	
	public void cancel() {
		
		cancelled = true;
		running = false;
		updates = false;
		
		instance.remove(this);
		
		stopSendingUpdates();
		clearAnimations();
		dropHolder();
		
		removeChannelIfIncomplete();
	}
	
	/**
	 * Get the target server for this reconnecter
	 * @return The target of this reconnecter
	 */
	public BungeeServerInfo getTarget() {
		return target;
	}
	
	/**
	 * Get the UUID this reconnecter is trying to reconnect
	 * @return The target of this reconnecter
	 */
	public UUID getUUID() {
		return user.getUniqueId();
	}

}
