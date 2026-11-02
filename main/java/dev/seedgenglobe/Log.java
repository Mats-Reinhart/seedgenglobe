package dev.seedgenglobe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logger holder with no other dependencies, safe to use from the mixin plugin before the game loads. */
public final class Log {

	public static final Logger LOGGER = LoggerFactory.getLogger("SeedGenGlobe");

	private Log() {}
}
