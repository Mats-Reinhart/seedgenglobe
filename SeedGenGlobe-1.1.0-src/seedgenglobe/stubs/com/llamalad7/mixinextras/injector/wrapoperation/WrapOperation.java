package com.llamalad7.mixinextras.injector.wrapoperation;

import java.lang.annotation.*;

import org.spongepowered.asm.mixin.injection.At;

/** Compile-only stub of MixinExtras' annotation (MixinExtras ships with Fabric Loader). */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WrapOperation {
	String[] method() default {};
	At[] at() default {};
	boolean remap() default true;
	int require() default -1;
	int expect() default 1;
	int allow() default -1;
}
