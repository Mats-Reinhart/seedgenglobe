package org.spongepowered.asm.mixin;

import java.lang.annotation.*;

/** Compile-only stub of Sponge Mixin's annotation (the real one ships with Fabric Loader). */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Mixin {
	Class<?>[] value() default {};
	String[] targets() default {};
	int priority() default 1000;
	boolean remap() default true;
}
