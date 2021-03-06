package eu.the5zig.reconnect.util.scheduler;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import eu.the5zig.reconnect.Reconnect;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Plugin;

public class Sched {

	/**
	 * Schedules a task and executes it asynchronously.
	 *
	 * @param runnable The Runnable that should be executed asynchronously after the specified time.
	 * @param time     The amount of time the task should be scheduled.
	 * @param timeUnit The {@link TimeUnit} of the time parameter.
	 */
	public static void scheduleAsync(final Reconnect instance, final Runnable runnable, long time, TimeUnit timeUnit) {
		ProxyServer.getInstance().getScheduler().schedule(instance, new Runnable() {
			@Override
			public void run() {
				ProxyServer.getInstance().getScheduler().runAsync(instance, runnable);
			}
		}, time, timeUnit);
	}
	
	public static <T> CompletableFuture<T> callOnMainThread(Plugin p, Callable<T> call) {
		CompletableFuture<T> future = new CompletableFuture<T>();
		try {
			p.getProxy().getScheduler().schedule(p, () -> {
				try {
					future.complete(call.call());
				} catch (Exception e) {
					p.getLogger().log(Level.SEVERE, "exception while calling method on main thread", e);
					future.completeExceptionally(new CompletionException("exception while completing task", e));
				}
			}, 0, TimeUnit.NANOSECONDS);
		} catch (Exception e) {
			p.getLogger().log(Level.SEVERE, "exception while scheduling main thread task", e);
			future.completeExceptionally(new ExecutionException("exception while scheduling main thread task", e));
		}
		return future;
	}

}
