package dev.seedgenglobe;

import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Decides which of SeedGenGlobe's Distant Horizons mixins to apply.
 * <p>
 * The mixins touch Distant Horizons internals that are not part of its API, so before applying one this plugin
 * reads the target class file (without loading it) and checks that every field and method the mixin relies on is
 * present with the expected type. A mixin that does not fit the installed Distant Horizons build is skipped with
 * a log line instead of crashing the game; the generator itself only uses DH's public API and keeps working.
 */
public final class GlobeMixinPlugin implements IMixinConfigPlugin {

	private static final String DH = "com/seibel/distanthorizons/";

	/** mixin simple name -> required members, each "Owner#name#descriptor" (descriptor of a field or a method). */
	private static final Map<String, String[]> REQUIREMENTS = Map.of(
		"DhClientLevelMixin", new String[] {
			DH + "core/level/DhClientLevel#shouldDoWorldGen#()Z",
			DH + "core/level/DhClientLevel#networkState#L" + DH + "core/multiplayer/client/ClientNetworkState;",
		},
		"RemoteWorldRetrievalQueueMixin", new String[] {
			DH + "core/generation/queues/RemoteWorldRetrievalQueue#submitRetrievalTask#(JB)Ljava/util/concurrent/CompletableFuture;",
			DH + "core/generation/queues/RemoteWorldRetrievalQueue#startAndSetTargetPos#(L" + DH + "core/pos/blockPos/DhBlockPos2D;)V",
			DH + "core/generation/queues/RemoteWorldRetrievalQueue#getRequestRateLimit#()I",
			DH + "core/multiplayer/client/AbstractFullDataNetworkRequestQueue#level#L" + DH + "core/level/DhClientLevel;",
			DH + "core/multiplayer/client/AbstractFullDataNetworkRequestQueue#networkState#L" + DH + "core/multiplayer/client/ClientNetworkState;",
			DH + "core/multiplayer/client/AbstractFullDataNetworkRequestQueue#<init>#(L" + DH + "core/multiplayer/client/ClientNetworkState;L" + DH + "core/level/DhClientLevel;ZL" + DH + "core/config/types/ConfigEntry;)V",
		},
		"NetworkRequestQueueMixin", new String[] {
			DH + "core/multiplayer/client/AbstractFullDataNetworkRequestQueue#level#L" + DH + "core/level/DhClientLevel;",
			DH + "core/multiplayer/client/AbstractFullDataNetworkRequestQueue#networkState#L" + DH + "core/multiplayer/client/ClientNetworkState;",
			DH + "core/multiplayer/client/AbstractFullDataNetworkRequestQueue#getWaitingTaskCount#()I",
			DH + "core/multiplayer/client/AbstractFullDataNetworkRequestQueue#getInProgressTaskCount#()I",
		},
		"SharedApiMixin", new String[] {
			DH + "core/api/internal/SharedApi#applyChunkUpdate#(L" + DH + "core/wrapperInterfaces/chunk/IChunkWrapper;L" + DH + "core/wrapperInterfaces/world/ILevelWrapper;)V",
		},
		"ClientBlockStateColorCacheMixin", new String[] {
			DH + "common/wrappers/block/ClientBlockStateColorCache#getColor#(L" + DH + "common/wrappers/block/BiomeWrapper;L" + DH + "core/dataObjects/fullData/sources/FullDataSourceV2;L" + DH + "core/pos/blockPos/DhBlockPos;)I",
			DH + "common/wrappers/block/ClientBlockStateColorCache#clientLevelWrapper#L" + DH + "core/wrapperInterfaces/world/IClientLevelWrapper;",
			DH + "common/wrappers/block/ClientBlockStateColorCache#blockState#Lnet/minecraft/world/level/block/state/BlockState;",
			DH + "common/wrappers/block/ClientBlockStateColorCache#blockStateWrapper#L" + DH + "common/wrappers/block/BlockStateWrapper;",
			DH + "common/wrappers/block/ClientBlockStateColorCache#baseColor#I",
			DH + "common/wrappers/block/ClientBlockStateColorCache#needPostTinting#Z",
			DH + "common/wrappers/block/ClientBlockStateColorCache#tintIndex#I",
		}
	);

	/** Mixins left out because the installed Distant Horizons does not fit them, with the reason. Shown by the status commands. */
	public static final Map<String, String> SKIPPED = new ConcurrentHashMap<>();
	/** Mixins that were applied to their target class (targets are transformed when first loaded). */
	public static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();

	private final Map<String, ClassModel> classes = new HashMap<>();

	@Override
	public void onLoad(String mixinPackage) {
		GlobeConfig.load();
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		GlobeConfig config = GlobeConfig.get();
		if (!config.enabled || !ModCompat.DISTANT_HORIZONS || !ModCompat.BIG_GLOBE) return false;
		boolean wanted = switch (name) {
			case "DhClientLevelMixin", "RemoteWorldRetrievalQueueMixin", "NetworkRequestQueueMixin" -> config.clientGeneration;
			case "SharedApiMixin" -> ModCompat.VERTIGO && config.vertigoGuard;
			case "ClientBlockStateColorCacheMixin" -> config.bigGlobeColors;
			default -> true;
		};
		if (!wanted) return false;
		String problem = this.problem(name);
		if (problem != null) {
			SKIPPED.put(name, problem);
			Log.LOGGER.warn("SeedGenGlobe: leaving out {} for Distant Horizons {}: {}", name, ModCompat.version("distanthorizons"), problem);
			return false;
		}
		return true;
	}

	/** Human readable lines about mixins that could not be applied, empty when all is well. */
	public static List<String> problems() {
		List<String> lines = new ArrayList<>();
		for (Map.Entry<String, String> entry : SKIPPED.entrySet()) {
			String feature = switch (entry.getKey()) {
				case "DhClientLevelMixin", "RemoteWorldRetrievalQueueMixin", "NetworkRequestQueueMixin" -> "multiplayer client generation";
				case "SharedApiMixin" -> "the Vertigo guard";
				case "ClientBlockStateColorCacheMixin" -> "Big Globe colours for LODs";
				default -> entry.getKey();
			};
			lines.add("Not available with this Distant Horizons build: " + feature + " (" + entry.getValue() + ")");
		}
		return lines;
	}

	private String problem(String mixinName) {
		String[] requirements = REQUIREMENTS.get(mixinName);
		if (requirements == null) return null;
		for (String requirement : requirements) {
			String[] parts = requirement.split("#", 3);
			ClassModel model = this.read(parts[0]);
			if (model == null) return "class " + parts[0] + " not found";
			if (!has(model, parts[1], parts[2])) return "missing " + parts[1] + " " + parts[2] + " in " + parts[0];
		}
		return null;
	}

	private static boolean has(ClassModel model, String name, String descriptor) {
		if (descriptor.startsWith("(")) {
			for (MethodModel method : model.methods()) {
				if (method.methodName().equalsString(name) && method.methodType().equalsString(descriptor)) return true;
			}
		}
		else {
			for (FieldModel field : model.fields()) {
				if (field.fieldName().equalsString(name) && field.fieldType().equalsString(descriptor)) return true;
			}
		}
		return false;
	}

	private ClassModel read(String internalName) {
		return this.classes.computeIfAbsent(internalName, n -> {
			try (InputStream stream = GlobeMixinPlugin.class.getClassLoader().getResourceAsStream(n + ".class")) {
				return stream == null ? null : ClassFile.of().parse(stream.readAllBytes());
			}
			catch (Exception | LinkageError exception) {
				return null;
			}
		});
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		APPLIED.add(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
	}
}
