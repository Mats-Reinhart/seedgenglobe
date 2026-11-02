package net.minecraft.core;

import java.util.Optional;

import net.minecraft.resources.ResourceKey;

public interface Holder<T> {

	T value();

	default Optional<ResourceKey<T>> unwrapKey() {
		return Optional.empty();
	}
}
