import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Checks that a built mod jar links against a set of real jars: the Minecraft jar of the version to port to and the
 * mods and libraries of that environment. Unlike LinkCheck, nothing is taken on trust from other mods' references:
 * every class, field and method SeedGenGlobe's bytecode uses outside its own classes must exist in those jars (or the
 * running JDK), found the way the JVM resolves it (superclasses, then interfaces), with the same static-ness and
 * class-or-interface kind. Mixins are checked against their real target classes: every target class, @Shadow member,
 * @Inject method (and handler signature), @At INVOKE target and MixinExtras @WrapOperation call.
 * <p>
 * Members looked up by name at runtime (reflection, method handles, Mixin plugin requirement strings) do not show in
 * bytecode and are not checked here.
 * <p>
 * usage: java PortCheck.java [--quiet] mod.jar jar...    (exit status 1 when anything is missing)
 */
public class PortCheck {

	record Found(String owner, int flags, boolean ownerInterface) {}

	static final Map<String, ClassModel> available = new HashMap<>();
	static final Map<String, ClassModel> mine = new HashMap<>();
	static final Set<String> errors = new TreeSet<>(), warnings = new TreeSet<>(), mixinOk = new TreeSet<>();
	static int checked;

	public static void main(String[] args) throws IOException {
		int first = 0;
		boolean quiet = false;
		if (args.length > 0 && args[0].equals("--quiet")) {
			quiet = true;
			first = 1;
		}
		if (args.length - first < 2) {
			System.err.println("usage: java PortCheck.java [--quiet] mod.jar jar...");
			System.exit(2);
		}
		load(Files.readAllBytes(Path.of(args[first])), mine, true);
		for (int index = first + 1; index < args.length; index++) load(Files.readAllBytes(Path.of(args[index])), available, false);
		for (ClassModel model : mine.values()) check(model);
		if (!quiet) for (String line : mixinOk) System.out.println(line);
		for (String line : warnings) System.out.println(line);
		for (String line : errors) System.out.println(line);
		requirements();
		System.out.printf("%n%d references and mixin points checked: %d missing or wrong, %d warnings%n", checked, errors.size(), warnings.size());
		if (!errors.isEmpty()) System.exit(1);
	}

	static void load(byte[] jar, Map<String, ClassModel> into, boolean top) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				String name = entry.getName();
				if (name.endsWith(".class") && !name.startsWith("META-INF/versions/") && !name.endsWith("module-info.class")) {
					try {
						ClassModel model = ClassFile.of().parse(zip.readAllBytes());
						into.putIfAbsent(model.thisClass().asInternalName(), model);
					}
					catch (RuntimeException unreadable) {
						if (top) throw unreadable;
					}
				}
				// libraries nested in mods (Big Globe's autocodec, Xaero's xaerolib, Fabric API's modules)
				else if (name.endsWith(".jar") && !top) {
					load(zip.readAllBytes(), into, false);
				}
			}
		}
	}

	// ------------------------------------------------------------------------------------------------ classes

	static boolean jdk(String name) {
		return name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("jdk/") || name.startsWith("sun/");
	}

	static String element(String name) {
		while (name.startsWith("[")) name = name.substring(1);
		if (name.startsWith("L") && name.endsWith(";")) return name.substring(1, name.length() - 1);
		return name.length() == 1 ? null : name; // primitive array
	}

	static ClassModel model(String name) {
		ClassModel model = mine.get(name);
		return model != null ? model : available.get(name);
	}

	static Class<?> jdkClass(String name) {
		try {
			return Class.forName(name.replace('/', '.'), false, PortCheck.class.getClassLoader());
		}
		catch (ClassNotFoundException | LinkageError missing) {
			return null;
		}
	}

	static boolean exists(String name) {
		name = element(name);
		if (name == null) return true;
		if (model(name) != null) return true;
		return jdk(name) && jdkClass(name) != null;
	}

	static void type(String from, String name) {
		checked++;
		if (!exists(name)) errors.add("MISSING class " + element(name) + "   (used by " + from + ")");
	}

	static void descriptorTypes(String from, String descriptor) {
		MethodTypeDesc type = descriptor.startsWith("(") ? MethodTypeDesc.ofDescriptor(descriptor) : null;
		List<ClassDesc> types = new ArrayList<>();
		if (type != null) {
			types.addAll(type.parameterList());
			types.add(type.returnType());
		}
		else {
			types.add(ClassDesc.ofDescriptor(descriptor));
		}
		for (ClassDesc desc : types) {
			if (desc.isPrimitive()) continue;
			String name = element(desc.descriptorString());
			if (name != null && !exists(name)) warnings.add("WARN    " + from + " has missing class " + name + " in a signature");
		}
	}

	// ------------------------------------------------------------------------------------------------ members

	/** Finds a member the way the JVM resolves it: the class and its superclasses, then its interfaces. */
	static Found resolve(String owner, String name, String descriptor, boolean method, Set<String> visited) {
		if (!visited.add(owner)) return null;
		ClassModel model = model(owner);
		if (model == null) return jdk(owner) ? resolveJdk(owner, name, descriptor, method) : null;
		boolean itf = model.flags().has(AccessFlag.INTERFACE);
		if (method) {
			for (MethodModel candidate : model.methods()) {
				if (candidate.methodName().equalsString(name) && candidate.methodType().equalsString(descriptor)) return new Found(owner, candidate.flags().flagsMask(), itf);
			}
		}
		else {
			for (FieldModel candidate : model.fields()) {
				if (candidate.fieldName().equalsString(name) && candidate.fieldType().equalsString(descriptor)) return new Found(owner, candidate.flags().flagsMask(), itf);
			}
		}
		if (method && name.equals("<init>")) return null; // constructors are not inherited
		if (model.superclass().isPresent()) {
			Found found = resolve(model.superclass().get().asInternalName(), name, descriptor, method, visited);
			if (found != null) return found;
		}
		for (ClassEntry face : model.interfaces()) {
			Found found = resolve(face.asInternalName(), name, descriptor, method, visited);
			if (found != null) return found;
		}
		return null;
	}

	static Found resolveJdk(String owner, String name, String descriptor, boolean method) {
		Class<?> type = jdkClass(owner);
		if (type == null) return null;
		boolean itf = type.isInterface();
		// MethodHandle.invoke and friends take any arguments
		if (method && (owner.equals("java/lang/invoke/MethodHandle") || owner.equals("java/lang/invoke/VarHandle"))) {
			for (Method candidate : type.getDeclaredMethods()) {
				if (candidate.getName().equals(name) && candidate.isVarArgs() && Modifier.isNative(candidate.getModifiers())) return new Found(owner, candidate.getModifiers(), itf);
			}
		}
		if (method && name.equals("<init>")) {
			for (Constructor<?> candidate : type.getDeclaredConstructors()) {
				if (descriptor(candidate.getParameterTypes(), void.class).equals(descriptor)) return new Found(owner, candidate.getModifiers(), itf);
			}
			return null;
		}
		List<Class<?>> chain = new ArrayList<>();
		for (Class<?> current = type; current != null; current = current.getSuperclass()) chain.add(current);
		List<Class<?>> faces = new ArrayList<>();
		collectInterfaces(type, faces, new HashSet<>());
		chain.addAll(faces);
		if (type.isInterface()) chain.add(Object.class);
		for (Class<?> current : chain) {
			if (method) {
				for (Method candidate : current.getDeclaredMethods()) {
					if (candidate.getName().equals(name) && descriptor(candidate.getParameterTypes(), candidate.getReturnType()).equals(descriptor)) {
						return new Found(current.getName().replace('.', '/'), candidate.getModifiers(), current.isInterface());
					}
				}
			}
			else {
				for (Field candidate : current.getDeclaredFields()) {
					if (candidate.getName().equals(name) && candidate.getType().descriptorString().equals(descriptor)) {
						return new Found(current.getName().replace('.', '/'), candidate.getModifiers(), current.isInterface());
					}
				}
			}
		}
		return null;
	}

	static void collectInterfaces(Class<?> type, List<Class<?>> into, Set<Class<?>> seen) {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			for (Class<?> face : current.getInterfaces()) {
				if (seen.add(face)) {
					into.add(face);
					collectInterfaces(face, into, seen);
				}
			}
		}
	}

	static String descriptor(Class<?>[] parameters, Class<?> result) {
		StringBuilder builder = new StringBuilder("(");
		for (Class<?> parameter : parameters) builder.append(parameter.descriptorString());
		return builder.append(')').append(result.descriptorString()).toString();
	}

	/**
	 * A field or method reference from one of the mod's classes. References to the mod's own classes are followed to
	 * the class outside the mod they inherit the member from.
	 *
	 * @param isStatic whether the instruction expects a static member
	 * @param expectsInterface for invokes: whether the instruction names an interface method (null: no expectation)
	 */
	static void member(String from, String owner, String name, String descriptor, boolean method, boolean isStatic, Boolean expectsInterface) {
		String ownerType = element(owner);
		if (ownerType == null || owner.startsWith("[")) return; // array methods (clone) and primitives
		checked++;
		if (!exists(ownerType)) {
			errors.add("MISSING class " + ownerType + "   (owner of " + name + descriptor + ", used by " + from + ")");
			return;
		}
		Found found = resolve(ownerType, name, descriptor, method, new HashSet<>());
		String what = ownerType + "." + name + (method ? descriptor : " " + descriptor);
		if (found == null) {
			errors.add("MISSING " + (method ? "method " : "field ") + what + "   (used by " + from + ")");
			return;
		}
		if (mine.containsKey(found.owner())) return; // the mod's own member
		if (Modifier.isStatic(found.flags()) != isStatic) {
			errors.add("WRONG   " + what + " is " + (isStatic ? "not " : "") + "static now   (used by " + from + ")");
		}
		if (method && expectsInterface != null) {
			ClassModel ownerModel = model(ownerType);
			boolean ownerInterface = ownerModel != null ? ownerModel.flags().has(AccessFlag.INTERFACE) : jdkClass(ownerType) != null && jdkClass(ownerType).isInterface();
			if (ownerInterface != expectsInterface) {
				errors.add("WRONG   " + ownerType + " is " + (ownerInterface ? "an interface" : "a class") + " now (" + name + descriptor + ")   (used by " + from + ")");
			}
		}
		if (Modifier.isPrivate(found.flags()) && !found.owner().equals(ownerType)) {
			errors.add("WRONG   " + what + " is private in " + found.owner() + "   (used by " + from + ")");
		}
	}

	// ------------------------------------------------------------------------------------------------ bytecode

	static void check(ClassModel model) {
		String self = model.thisClass().asInternalName();
		model.superclass().ifPresent(parent -> type(self, parent.asInternalName()));
		for (ClassEntry face : model.interfaces()) type(self, face.asInternalName());
		for (PoolEntry entry : model.constantPool()) {
			if (!(entry instanceof ClassEntry classEntry)) continue;
			String name = element(classEntry.asInternalName());
			if (name != null && !exists(name)) warnings.add("WARN    " + self + " names missing class " + name);
		}
		for (FieldModel field : model.fields()) descriptorTypes(self + "." + field.fieldName(), field.fieldType().stringValue());
		for (MethodModel method : model.methods()) {
			String where = self + "." + method.methodName().stringValue();
			descriptorTypes(where, method.methodType().stringValue());
			method.code().ifPresent(code -> {
				for (CodeElement element : code) {
					if (element instanceof InvokeInstruction invoke) {
						Opcode opcode = invoke.opcode();
						Boolean itf = opcode == Opcode.INVOKESPECIAL && invoke.name().equalsString("<init>") ? null : invoke.isInterface();
						member(where, invoke.owner().asInternalName(), invoke.name().stringValue(), invoke.type().stringValue(), true, opcode == Opcode.INVOKESTATIC, itf);
					}
					else if (element instanceof FieldInstruction field) {
						boolean isStatic = field.opcode() == Opcode.GETSTATIC || field.opcode() == Opcode.PUTSTATIC;
						member(where, field.owner().asInternalName(), field.name().stringValue(), field.type().stringValue(), false, isStatic, null);
					}
					else if (element instanceof NewObjectInstruction create) {
						type(where, create.className().asInternalName());
					}
					else if (element instanceof NewReferenceArrayInstruction array) {
						type(where, array.componentType().asInternalName());
					}
					else if (element instanceof TypeCheckInstruction cast) {
						type(where, cast.type().asInternalName());
					}
					else if (element instanceof ConstantInstruction constant && constant.constantValue() instanceof ClassDesc literal && !literal.isPrimitive()) {
						String name = element(literal.descriptorString());
						if (name != null) type(where, name);
					}
					else if (element instanceof InvokeDynamicInstruction dynamic) {
						for (ConstantDesc argument : dynamic.bootstrapArgs()) {
							if (argument instanceof DirectMethodHandleDesc handle) {
								String owner = element(handle.owner().descriptorString());
								DirectMethodHandleDesc.Kind kind = handle.kind();
								boolean isField = kind == DirectMethodHandleDesc.Kind.GETTER || kind == DirectMethodHandleDesc.Kind.SETTER
									|| kind == DirectMethodHandleDesc.Kind.STATIC_GETTER || kind == DirectMethodHandleDesc.Kind.STATIC_SETTER;
								boolean isStatic = kind == DirectMethodHandleDesc.Kind.STATIC || kind == DirectMethodHandleDesc.Kind.INTERFACE_STATIC
									|| kind == DirectMethodHandleDesc.Kind.STATIC_GETTER || kind == DirectMethodHandleDesc.Kind.STATIC_SETTER;
								// a field's type, or a method's or constructor's descriptor
								String descriptor = handle.lookupDescriptor();
								member(where + " (method reference)", owner, handle.methodName(), descriptor, !isField, isStatic, isField || kind == DirectMethodHandleDesc.Kind.CONSTRUCTOR ? null : handle.isOwnerInterface());
							}
						}
					}
				}
			});
		}
		mixin(model);
	}

	// ------------------------------------------------------------------------------------------------ mixin plugin

	/**
	 * The requirement strings of SeedGenGlobe's mixin plugin ("Owner#name#descriptor": a member that must exist,
	 * "Owner#method#*#CallOwner.name(descriptor)": a method that must contain that call), read from its constant
	 * pool, checked against the jars: the plugin leaves out a mixin whose requirement fails. Listed, not counted as
	 * errors: that is what the requirements are for.
	 */
	static void requirements() {
		for (ClassModel model : mine.values()) {
			if (!model.thisClass().asInternalName().endsWith("MixinPlugin")) continue;
			int passed = 0;
			List<String> failed = new ArrayList<>();
			for (PoolEntry entry : model.constantPool()) {
				if (!(entry instanceof java.lang.classfile.constantpool.StringEntry text)) continue;
				String requirement = text.stringValue();
				String[] parts = requirement.split("#", 4);
				if (parts.length < 3 || parts[0].contains(" ") || !parts[0].contains("/")) continue;
				ClassModel owner = model(parts[0]);
				boolean ok;
				if (owner == null) ok = false;
				else if (parts.length == 4) {
					int dot = parts[3].indexOf('.');
					ok = dot > 0 && owner.methods().stream()
						.filter(method -> method.methodName().equalsString(parts[1]))
						.anyMatch(method -> calls(method, "L" + parts[3].substring(0, dot) + ";" + parts[3].substring(dot + 1)));
				}
				else if (parts[2].startsWith("(")) ok = owner.methods().stream().anyMatch(m -> m.methodName().equalsString(parts[1]) && m.methodType().equalsString(parts[2]));
				else ok = owner.fields().stream().anyMatch(f -> f.fieldName().equalsString(parts[1]) && f.fieldType().equalsString(parts[2]));
				if (ok) passed++;
				else failed.add("  fails: " + requirement + (owner == null ? "  (no such class)" : ""));
			}
			System.out.printf("%nmixin plugin %s: %d requirements met, %d not (their mixins are left out)%n", model.thisClass().asInternalName(), passed, failed.size());
			for (String line : failed) System.out.println(line);
		}
	}

	// ------------------------------------------------------------------------------------------------ mixins

	static List<Annotation> annotations(Optional<? extends java.lang.classfile.Attribute<?>> visible, Optional<? extends java.lang.classfile.Attribute<?>> invisible) {
		List<Annotation> all = new ArrayList<>();
		visible.ifPresent(attribute -> all.addAll(((java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute)(attribute)).annotations()));
		invisible.ifPresent(attribute -> all.addAll(((java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute)(attribute)).annotations()));
		return all;
	}

	static List<Annotation> annotations(ClassModel model) {
		return annotations(model.findAttribute(Attributes.runtimeVisibleAnnotations()), model.findAttribute(Attributes.runtimeInvisibleAnnotations()));
	}

	static List<Annotation> annotations(MethodModel model) {
		return annotations(model.findAttribute(Attributes.runtimeVisibleAnnotations()), model.findAttribute(Attributes.runtimeInvisibleAnnotations()));
	}

	static List<Annotation> annotations(FieldModel model) {
		return annotations(model.findAttribute(Attributes.runtimeVisibleAnnotations()), model.findAttribute(Attributes.runtimeInvisibleAnnotations()));
	}

	static Optional<AnnotationValue> value(Annotation annotation, String name) {
		for (AnnotationElement element : annotation.elements()) if (element.name().equalsString(name)) return Optional.of(element.value());
		return Optional.empty();
	}

	static List<String> strings(Optional<AnnotationValue> value) {
		List<String> result = new ArrayList<>();
		value.ifPresent(v -> {
			if (v instanceof AnnotationValue.OfArray array) {
				for (AnnotationValue item : array.values()) if (item instanceof AnnotationValue.OfString text) result.add(text.stringValue());
			}
			else if (v instanceof AnnotationValue.OfString text) result.add(text.stringValue());
		});
		return result;
	}

	static void mixin(ClassModel model) {
		String self = model.thisClass().asInternalName();
		List<String> targets = new ArrayList<>();
		for (Annotation annotation : annotations(model)) {
			if (!annotation.className().equalsString("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
			value(annotation, "value").ifPresent(v -> {
				if (v instanceof AnnotationValue.OfArray array) {
					for (AnnotationValue item : array.values()) if (item instanceof AnnotationValue.OfClass type) targets.add(element(type.className().stringValue()));
				}
			});
			for (String target : strings(value(annotation, "targets"))) targets.add(target.replace('.', '/'));
		}
		for (String target : targets) {
			checked++;
			ClassModel targetModel = model(target);
			if (targetModel == null) {
				errors.add("MISSING mixin target " + target + "   (" + self + ")");
				continue;
			}
			mixinOk.add("OK      mixin " + self + " -> " + target);
			for (FieldModel field : model.fields()) {
				if (annotations(field).stream().noneMatch(a -> a.className().equalsString("Lorg/spongepowered/asm/mixin/Shadow;"))) continue;
				checked++;
				Found found = resolve(target, field.fieldName().stringValue(), field.fieldType().stringValue(), false, new HashSet<>());
				if (found == null) errors.add("MISSING @Shadow field " + target + "." + field.fieldName() + " " + field.fieldType() + "   (" + self + ")");
			}
			for (MethodModel method : model.methods()) {
				List<Annotation> list = annotations(method);
				if (list.stream().anyMatch(a -> a.className().equalsString("Lorg/spongepowered/asm/mixin/Shadow;"))) {
					checked++;
					if (resolve(target, method.methodName().stringValue(), method.methodType().stringValue(), true, new HashSet<>()) == null) {
						errors.add("MISSING @Shadow method " + target + "." + method.methodName() + method.methodType() + "   (" + self + ")");
					}
				}
				for (Annotation annotation : list) {
					String kind = annotation.className().stringValue();
					if (kind.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) inject(self, target, targetModel, method, annotation);
					else if (kind.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")) wrap(self, target, targetModel, method, annotation);
				}
			}
		}
	}

	static List<MethodModel> selected(ClassModel target, String selector) {
		String name = selector.contains("(") ? selector.substring(0, selector.indexOf('(')) : selector;
		String descriptor = selector.contains("(") ? selector.substring(selector.indexOf('(')) : null;
		return target.methods().stream().filter(m -> m.methodName().equalsString(name) && (descriptor == null || m.methodType().equalsString(descriptor))).toList();
	}

	/** INVOKE targets of the @At annotations of an injector, as "Lowner;name(desc)". */
	static List<String> invokeTargets(Annotation annotation) {
		List<String> calls = new ArrayList<>();
		value(annotation, "at").ifPresent(v -> {
			List<AnnotationValue> items = v instanceof AnnotationValue.OfArray array ? array.values() : List.of(v);
			for (AnnotationValue item : items) {
				if (!(item instanceof AnnotationValue.OfAnnotation at)) continue;
				List<String> kind = strings(value(at.annotation(), "value"));
				List<String> call = strings(value(at.annotation(), "target"));
				if (!kind.isEmpty() && kind.get(0).equals("INVOKE") && !call.isEmpty()) calls.add(call.get(0));
			}
		});
		return calls;
	}

	static boolean calls(MethodModel method, String call) {
		int semi = call.indexOf(';'), paren = call.indexOf('(');
		if (!call.startsWith("L") || semi < 0 || paren < semi) return false;
		String owner = call.substring(1, semi), name = call.substring(semi + 1, paren), descriptor = call.substring(paren);
		if (method.code().isEmpty()) return false;
		for (CodeElement element : method.code().get()) {
			if (element instanceof InvokeInstruction invoke && invoke.owner().asInternalName().equals(owner) && invoke.name().equalsString(name) && invoke.type().equalsString(descriptor)) return true;
		}
		return false;
	}

	static void inject(String self, String target, ClassModel targetModel, MethodModel handler, Annotation annotation) {
		for (String selector : strings(value(annotation, "method"))) {
			checked++;
			List<MethodModel> matches = selected(targetModel, selector);
			if (matches.isEmpty()) {
				errors.add("MISSING @Inject target " + target + "." + selector + "   (" + self + "." + handler.methodName() + ")");
				continue;
			}
			for (MethodModel method : matches) {
				MethodTypeDesc targetType = method.methodTypeSymbol(), handlerType = handler.methodTypeSymbol();
				String callback = targetType.returnType().descriptorString().equals("V")
					? "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;"
					: "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
				int count = targetType.parameterCount();
				boolean fits = handlerType.parameterCount() == 1 && handlerType.parameterType(0).descriptorString().equals(callback);
				if (!fits && handlerType.parameterCount() >= count + 1 && handlerType.parameterType(count).descriptorString().equals(callback)) {
					fits = true;
					for (int index = 0; fits && index < count; index++) fits = handlerType.parameterType(index).equals(targetType.parameterType(index));
				}
				if (!fits) errors.add("WRONG   @Inject handler " + self + "." + handler.methodName() + handlerType.descriptorString() + " does not fit " + target + "." + method.methodName() + targetType.descriptorString());
				for (String call : invokeTargets(annotation)) {
					checked++;
					if (!calls(method, call)) errors.add("MISSING @At INVOKE " + call + " in " + target + "." + method.methodName() + method.methodType() + "   (" + self + "." + handler.methodName() + ")");
				}
				if (fits) mixinOk.add("OK      @Inject " + self + "." + handler.methodName() + " -> " + target + "." + method.methodName() + targetType.descriptorString());
			}
		}
	}

	static void wrap(String self, String target, ClassModel targetModel, MethodModel handler, Annotation annotation) {
		List<String> calls = invokeTargets(annotation);
		for (String selector : strings(value(annotation, "method"))) {
			checked++;
			List<MethodModel> matches = selected(targetModel, selector);
			if (matches.isEmpty()) {
				errors.add("MISSING @WrapOperation target " + target + "." + selector + "   (" + self + "." + handler.methodName() + ")");
				continue;
			}
			for (MethodModel method : matches) {
				for (String call : calls) {
					checked++;
					if (!calls(method, call)) {
						errors.add("MISSING @WrapOperation call " + call + " in " + target + "." + method.methodName() + method.methodType() + "   (" + self + "." + handler.methodName() + ")");
						continue;
					}
					int semi = call.indexOf(';'), paren = call.indexOf('(');
					String owner = call.substring(1, semi), descriptor = call.substring(paren);
					boolean isStatic = false;
					for (CodeElement element : method.code().get()) {
						if (element instanceof InvokeInstruction invoke && invoke.owner().asInternalName().equals(owner) && invoke.name().equalsString(call.substring(semi + 1, paren)) && invoke.type().equalsString(descriptor)) {
							isStatic = invoke.opcode() == Opcode.INVOKESTATIC;
						}
					}
					MethodTypeDesc callType = MethodTypeDesc.ofDescriptor(descriptor), handlerType = handler.methodTypeSymbol();
					List<ClassDesc> expected = new ArrayList<>();
					if (!isStatic) expected.add(ClassDesc.ofInternalName(owner));
					expected.addAll(callType.parameterList());
					expected.add(ClassDesc.of("com.llamalad7.mixinextras.injector.wrapoperation.Operation"));
					List<ClassDesc> actual = handlerType.parameterList();
					boolean fits = handlerType.returnType().equals(callType.returnType()) && actual.size() >= expected.size() && actual.subList(0, expected.size()).equals(expected);
					if (!fits) errors.add("WRONG   @WrapOperation handler " + self + "." + handler.methodName() + handlerType.descriptorString() + " does not fit " + call);
					else mixinOk.add("OK      @WrapOperation " + self + "." + handler.methodName() + " -> " + call + " in " + target + "." + method.methodName());
				}
			}
		}
	}
}
