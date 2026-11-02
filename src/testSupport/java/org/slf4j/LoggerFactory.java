package org.slf4j;

/**
 * Test-only SLF4J logger factory that prints to standard output, placed ahead of the compile-only stub on the offline
 * test classpath so code that logs can run without Minecraft's logging.
 */
public final class LoggerFactory {

	public static Logger getLogger(String name) {
		return new Printing(name);
	}

	public static Logger getLogger(Class<?> clazz) {
		return new Printing(clazz.getSimpleName());
	}

	static final class Printing implements Logger {

		final String name;

		Printing(String name) {
			this.name = name;
		}

		static String format(String format, Object... arguments) {
			StringBuilder out = new StringBuilder();
			int from = 0, index = 0;
			while (true) {
				int at = format.indexOf("{}", from);
				if (at < 0 || index >= arguments.length) break;
				out.append(format, from, at).append(arguments[index++]);
				from = at + 2;
			}
			out.append(format.substring(from));
			if (index < arguments.length && arguments[arguments.length - 1] instanceof Throwable throwable) out.append(" | ").append(throwable);
			return out.toString();
		}

		void print(String level, String text) {
			if (!Boolean.getBoolean("seedgenglobe.test.quiet")) System.out.println("[" + level + "] " + text);
		}

		@Override public String getName() { return this.name; }
		@Override public boolean isDebugEnabled() { return false; }
		@Override public void debug(String msg) {}
		@Override public void debug(String format, Object arg) {}
		@Override public void debug(String format, Object arg1, Object arg2) {}
		@Override public void debug(String format, Object... arguments) {}
		@Override public void info(String msg) { this.print("INFO", msg); }
		@Override public void info(String format, Object arg) { this.print("INFO", format(format, arg)); }
		@Override public void info(String format, Object arg1, Object arg2) { this.print("INFO", format(format, arg1, arg2)); }
		@Override public void info(String format, Object... arguments) { this.print("INFO", format(format, arguments)); }
		@Override public void info(String msg, Throwable t) { this.print("INFO", msg + " | " + t); }
		@Override public void warn(String msg) { this.print("WARN", msg); }
		@Override public void warn(String format, Object arg) { this.print("WARN", format(format, arg)); }
		@Override public void warn(String format, Object arg1, Object arg2) { this.print("WARN", format(format, arg1, arg2)); }
		@Override public void warn(String format, Object... arguments) { this.print("WARN", format(format, arguments)); }
		@Override public void warn(String msg, Throwable t) { this.print("WARN", msg + " | " + t); }
		@Override public void error(String msg) { this.print("ERROR", msg); }
		@Override public void error(String format, Object arg) { this.print("ERROR", format(format, arg)); }
		@Override public void error(String format, Object arg1, Object arg2) { this.print("ERROR", format(format, arg1, arg2)); }
		@Override public void error(String format, Object... arguments) { this.print("ERROR", format(format, arguments)); }
		@Override public void error(String msg, Throwable t) { this.print("ERROR", msg + " | " + t); }
	}
}
