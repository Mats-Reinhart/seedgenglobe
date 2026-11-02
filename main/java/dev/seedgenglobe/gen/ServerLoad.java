package dev.seedgenglobe.gen;

/**
 * How long the server's ticks take, measured from Fabric's server tick events, so LOD generation can step back while
 * the server cannot keep its 20 ticks per second ("Can't keep up!"). A paused singleplayer game does not tick at all,
 * which counts as keeping up: generation runs at full speed in the pause menu.
 */
public final class ServerLoad {

	/** A tick budget: 50 ms is 20 ticks per second. */
	static final double BEHIND_MILLIS = 52.0D, KEEPING_UP_MILLIS = 40.0D;
	/** Only ticks this recent count. */
	static final long RECENT_NANOS = 3_000_000_000L;

	private static volatile long tickStart, lastTickEnd;
	/** Exponential moving average of tick durations (nanoseconds), about two seconds of ticks. */
	private static volatile double average;

	private ServerLoad() {}

	/** Server thread, start of every tick. */
	public static void tickStarted() {
		tickStart = System.nanoTime();
	}

	/** Server thread, end of every tick. */
	public static void tickEnded() {
		long now = System.nanoTime();
		long start = tickStart;
		if (start == 0L) return;
		double duration = now - start;
		double previous = average;
		average = previous == 0.0D ? duration : previous + (duration - previous) / 40.0D;
		lastTickEnd = now;
	}

	/** The server stopped: forget its ticks. */
	public static void reset() {
		tickStart = 0L;
		lastTickEnd = 0L;
		average = 0.0D;
	}

	static boolean recent() {
		long end = lastTickEnd;
		return end != 0L && System.nanoTime() - end < RECENT_NANOS;
	}

	/** Average milliseconds per tick lately, or 0 without recent ticks. */
	public static double averageMillis() {
		return recent() ? average / 1.0e6D : 0.0D;
	}

	/** Ticks take longer than the server can afford. */
	public static boolean behind() {
		return recent() && average / 1.0e6D > BEHIND_MILLIS;
	}

	/** Ticks leave room to spare (or the server is not ticking). */
	public static boolean keepingUp() {
		return !recent() || average / 1.0e6D < KEEPING_UP_MILLIS;
	}
}
