package eu.the5zig.reconnect;

import com.google.common.base.Strings;
import eu.the5zig.reconnect.net.BasicChannelInitializer;
import eu.the5zig.reconnect.util.Utils;
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

import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class ReconnectTask {

	private static final TextComponent EMPTY = new TextComponent("");
	private static final Random rand = new Random();

	private final Reconnect instance;
	private final ProxyServer bungee;
	private final UserConnection user;
	private final ServerConnection server;
	private final BungeeServerInfo target;
	private final long startTime;
	
	private ScheduledTask reconnectUpdatesTask = null;
	private boolean reconnectUpdates = true;

	private int tries;

	public ReconnectTask(Reconnect instance, ProxyServer bungee, UserConnection user, ServerConnection server, long startTime) {
		this.instance = instance;
		this.bungee = bungee;
		this.user = user;
		this.server = server;
		this.target = server.getInfo();
		this.startTime = startTime;
	}

	/**
	 * Tries to reconnect the User to the specified Server. In case that fails, this method will be executed again
	 * after a short timeout.
	 */
	public void tryReconnect() {
		if (tries + 1 > instance.getMaxReconnectTries()) {
			// If we have reached the maximum reconnect limit, proceed BungeeCord-like.
			instance.cancelReconnectTask(user.getUniqueId());

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
			return;
		}

		//Send keep alive packet so user will not disconnect if the server takes that long to start;
		//idea by krusic22
		user.unsafe().sendPacket(new KeepAlive(rand.nextLong()));
		
		// If we are already connecting to a server, cancel the reconnect task.
		if (user.getPendingConnects().contains(target)) {
			instance.getLogger().warning("User already connecting to " + target);
			return;
		}

		// Add pending connection.
		user.getPendingConnects().add(target);

		// Add a try if the delay is not active
		if (startTime + instance.getDelayBeforeTrying() <= System.currentTimeMillis()) {
			tries++;
		}
		
		//Create runnable if not existing; send tiles and action bar updates every 200 Miliseconds
		startSendingReconnectUpdates();

		// Establish connection to the server.
		try {
			ChannelInitializer<Channel> initializer = new BasicChannelInitializer(bungee, user, target);
			ChannelFutureListener listener = new ChannelFutureListener() {
				@Override
				public void operationComplete(ChannelFuture future) throws Exception {
					if (future.isSuccess()
							&& startTime + instance.getDelayBeforeTrying() <= System.currentTimeMillis()) {
						// If reconnected successfully, remove from map and send another fancy title.
						instance.cancelReconnectTask(user.getUniqueId());

						//Sends keep alive packet while server does plugin things
						//Also sends titles and action bars
						sendConnectUpdates();
					} else {
						future.channel().close();

						// Schedule next reconnect.
						retryReconnect();
					}
				}
			};
			// Create a new Netty Bootstrap that contains the ChannelInitializer and the ChannelFutureListener.
			Bootstrap bootstrap = new Bootstrap().channel(PipelineUtils.getChannel()).group(server.getCh().getHandle().eventLoop()).handler(initializer).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, instance.getReconnectTimeout()).remoteAddress(target.getAddress());

			// Windows is bugged, multi homed users will just have to live with random connecting IPs
			if (user.getPendingConnection().getListener().isSetLocalAddress() && !PlatformDependent.isWindows()) {
				bootstrap.localAddress(user.getPendingConnection().getListener().getHost().getHostString(), 0);
			}
			
			bootstrap.connect().addListener(listener);
		} catch (ChannelException e) { //If an exception occurs on the channel, such as a readTimeoutException, or any other channel exception, retry the reconnect
			// Schedule next reconnect.
			retryReconnect();
		}	
	}
	
	public void retryReconnect() {
		// Remove pending reconnect because we will retry later on
		user.getPendingConnects().remove(target);
		Utils.scheduleAsync(instance, new Runnable() {
			@Override
			public void run() {
				// Only retry to reconnect the user if he is still online and hasn't been moved to another server.
				if (instance.isUserOnline(user) && Objects.equals(user.getServer(), server)) {
					tryReconnect();
				} else {
					instance.cancelReconnectTask(user.getUniqueId());
				}
			}
		}, instance.getReconnectMillis(), TimeUnit.MILLISECONDS);
	}
	
	private void sendConnectUpdates() {
		ProxyServer.getInstance().getScheduler().schedule(instance, () -> {
			if (Objects.equals(user.getServer(), server)) {//because server connection A will not be B after user moves, while server connection B will just be a reference held by this
				//Send keep alive packet so user will not disconnect if the server feels like not sending any for whatever reason;
				user.unsafe().sendPacket(new KeepAlive(rand.nextLong()));
				
				// Send fancy Title
				if (!instance.getConnectingTitle().isEmpty()) {
					createConnectingTitle().send(user);
				}

				// Send fancy Action Bar Message
				if (!instance.getConnectingActionBar().isEmpty()) {
					sendConnectActionBar(user);
				}
				sendConnectUpdates();
			} else {
				cancel();
			}
		}, 200L, TimeUnit.MILLISECONDS);
	}

	private void startSendingReconnectUpdates() {
		reconnectUpdatesTask = ProxyServer.getInstance().getScheduler().schedule(instance, () -> {
			if (reconnectUpdates && user.isConnected()) {
				// Send fancy Title
				if (!instance.getReconnectingTitle().isEmpty()) {
					createReconnectTitle().send(user);
				}

				// Send fancy Action Bar Message
				if (!instance.getReconnectingActionBar().isEmpty()) {
					sendReconnectActionBar(user);
				}
				startSendingReconnectUpdates();
			} else {
				stopSendingReconnectUpdates();
			}
		}, 200L, TimeUnit.MILLISECONDS);
	}
	
	private void stopSendingReconnectUpdates() {
		if (reconnectUpdatesTask != null) {
			reconnectUpdates = false;
			reconnectUpdatesTask.cancel();
			reconnectUpdatesTask = null;
		}
	}

	/**
	 * Sends an Action Bar Message containing the reconnect-text to the player.
	 */
	private void sendReconnectActionBar(UserConnection user) {
		user.sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(instance.getReconnectingActionBar().replace("{%dots%}", getDots())));
	}
	
	/**
	 * Sends an Action Bar Message containing the connect-text to the player.
	 */
	private void sendConnectActionBar(UserConnection user) {
		user.sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(instance.getConnectingActionBar().replace("{%dots%}", getDots())));
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
		title.title(new TextComponent(instance.getReconnectingTitle().replace("{%dots%}", getDots())));
		if (!instance.getReconnectingSubtitle().isEmpty()) {
			title.subTitle(new TextComponent(instance.getReconnectingSubtitle().replace("{%dots%}", getDots())));
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
		title.title(new TextComponent(instance.getConnectingTitle().replace("{%dots%}", getDots())));
		if (!instance.getConnectingSubtitle().isEmpty()) {
			title.subTitle(new TextComponent(instance.getConnectingSubtitle().replace("{%dots%}", getDots())));
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
			title.subTitle(new TextComponent(instance.getFailedSubtitle().replace("{%dots%}", getDots())));
		}		
		title.stay(120);
		title.fadeIn(0);
		title.fadeOut(10);

		return title;
	}

	/**
	 * @return a String that is made of dots for the "dots animation".
	 */
	private String getDots() {
		int time = (int) (System.currentTimeMillis() - startTime)/1000;
		String dots = "";

		for (int i = 0, max = time % 4; i < max; i++) {
			dots += ".";
		}

		return dots;
	}

	/**
	 * Resets the title and action bar message if the player is still online
	 */
	public void cancel() {
		stopSendingReconnectUpdates();
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
