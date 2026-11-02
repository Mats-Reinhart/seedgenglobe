package dev.seedgenglobe.terrain;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Block state helpers that work by property name, so they need no static references to property objects. */
public final class BlockStates {

	private BlockStates() {}

	/** Sets a property by name, leaving the state alone when it has no such property or value. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static BlockState with(BlockState state, String property, String value) {
		for (Object object : (Iterable<?>)(Object)(state.getProperties())) {
			if (object instanceof Property<?> candidate && candidate.getName().equals(property)) {
				Object parsed = candidate.getValue(value).orElse(null);
				if (parsed == null) return state;
				return (BlockState)(state.setValue((Property)(candidate), (Comparable)(parsed)));
			}
		}
		return state;
	}
}
