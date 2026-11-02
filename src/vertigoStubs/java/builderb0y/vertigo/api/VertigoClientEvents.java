package builderb0y.vertigo.api;

import net.fabricmc.fabric.api.event.Event;

/** Compile-time stub of Vertigo's public client events (MIT, github.com/Builderb0y/Vertigo). Not shipped. */
public class VertigoClientEvents {

	public static final Event<Load> SECTION_LOADED = null;
	public static final Event<Unload> SECTION_UNLOADED = null;

	public static interface Load {

		public abstract void onSectionLoaded(int sectionX, int sectionY, int sectionZ);
	}

	public static interface Unload {

		public abstract void onSectionUnloaded(int sectionX, int sectionY, int sectionZ);
	}
}
