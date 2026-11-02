package net.minecraft.resources;

/** Test fake: a named key. */
public final class ResourceKey<T> {

	public final String name;

	public ResourceKey(String name) {
		this.name = name;
	}

	@Override
	public String toString() {
		return this.name;
	}
}
