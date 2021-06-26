package eu.the5zig.reconnect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Preconditions;

public class Animation {
	
	private final String placeholder;
	private final Pattern placeholderPattern;
	private final long delayNanos;
	private final List<String> animation;
	
	public Animation(String placeholder, int delay, TimeUnit delayUnit, List<String> animation) {
		Preconditions.checkNotNull(placeholder, "placeholder is null");
		Preconditions.checkArgument(!placeholder.isEmpty(), "placeholder is empty");
		Preconditions.checkNotNull(delayUnit, "delay time unit is null");
		Preconditions.checkArgument(delay > 0, "delay is below 1");
		Preconditions.checkNotNull(animation, "animation is null");
		Preconditions.checkArgument(!animation.isEmpty(), "animation is empty");
		this.placeholder = placeholder;
		this.placeholderPattern = Pattern.compile(placeholder, 16);
		this.delayNanos = delayUnit.toNanos(delay);
		this.animation = Collections.unmodifiableList(new ArrayList<String>(animation));
	}
	
	public String animate(Reconnecter connecter, String string) {
		return placeholderPattern.matcher(string).replaceAll(Matcher.quoteReplacement(get(connecter)));
	}
	
	public String get(Reconnecter connecter) {
		return animation.get((int) ((System.nanoTime() - connecter.getStartNanos()) / delayNanos) % animation.size())
				.replace("%playerName%", connecter.getUser().getName());
	}
	
	public String getPlaceholder() {
		return placeholder;
	}
	
	public Pattern getPlaceholderPattern() {
		return placeholderPattern;
	}
	
	public long getDelay(TimeUnit unit) {
		return TimeUnit.NANOSECONDS.convert(delayNanos, unit);
	}
	
	public long getDelayNanos() {
		return delayNanos;
	}
	
	public List<String> getAnimation() {
		return animation;
	}
	
}
