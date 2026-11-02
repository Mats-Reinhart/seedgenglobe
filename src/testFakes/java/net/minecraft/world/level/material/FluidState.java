package net.minecraft.world.level.material;

public class FluidState {
	private final boolean empty;
	public FluidState(boolean empty) { this.empty = empty; }
	public boolean isEmpty() { return this.empty; }
}
