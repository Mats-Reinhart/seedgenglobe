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
 * Decides which of SeedGenGlobe's Distant Horizons and Xaero's map mixins to apply.
 * <p>
 * The mixins touch internals that are not part of those mods' APIs, so before applying one this plugin reads the
 * target class file (without loading it) and checks that every field, method and call the mixin relies on is
 * present with the expected type. A mixin that does not fit the installed build is skipped with a log line instead
 * of crashing the game; the generator itself only uses DH's public API and keeps working.
 */
public final class GlobeMixinPlugin implements IMixinConfigPlugin {

	private static final String DH = "com/seibel/distanthorizons/";

	private static final String MC_GET_CHUNK = "(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;";

	/**
	 * mixin simple name -> requirements, each "Owner#name#descriptor" (a field or a method that must exist) or
	 * "Owner#method#*#CallOwner.callName(callDescriptor)" (a method whose code must contain that call).
	 */
	private static final Map<String, String[]> REQUIREMENTS = Map.ofEntries(
		Map.entry("DhClientLevelMixin", new String[] {
			DH + "core/level/DhClientLevel#shouldDoWorldGen#()Z",
			DH + "core/level/DhClientLevel#networkState#L" + DH + "core/multiplayer/client/ClientNetworkState;",
		}),
		Map.entry("RemoteWorldRetrievalQueueMixin", new String[] {
			DH + "core/generation/queues/RemoteWorldRetrievalQueue#submitRetrievalTask#(JB)Ljava/util/concurrent/CompletableFuture;",
			DH + "core/generation/queues/RemoteWorldRetrievalQueue#startAndSetTargetPos#(L" + DH + "core/pos/blockPos/DhBlockPos2D;)V",
			DH + "core/generation/queues/RemoteWorldRetrievalQueue#getRequestRateLimit#()I",
			DH + "core/multiplayer/client/AbstractFullDataNetworkRequestQueue#level#L" + DH + "core/level/DhClientLevel;",
			DH + "core/multiplayer/client/AbstractFullDataNetworkRequestQueue#networkState#L" + DH + "core/multiplayer/client/ClientNetworkState;",
			DH + "core/multiplayer/client/AbstractFullDataNetworkRequestQueue#<init>#(L" + DH + "core/multiplayer/client/ClientNetworkState;L" + DH + "core/level/DhClientLevel;ZL" + DH + "core/config/types/ConfigEntry;)V",
		}),
		Map.entry("NetworkRequestQueueMixin", new String[] {
			DH + "core/multiplayer/client/AbstractFullDataNetworkRequestQueue#level#L" + DH + "core/level/DhClientLevel;",
			DH + "core/multiplayer/client/AbstractFullDataNetworkRequestQueue#networkState#L" + DH + "core/multiplayer/client/ClientNetworkState;",
			DH + "core/multiplayer/client/AbstractFullDataNetworkRequestQueue#getWaitingTaskCount#()I",
			DH + "core/multiplayer/client/AbstractFullDataNetworkRequestQueue#getInProgressTaskCount#()I",
		}),
		Map.entry("SharedApiMixin", new String[] {
			DH + "core/api/internal/SharedApi#applyChunkUpdate#(L" + DH + "core/wrapperInterfaces/chunk/IChunkWrapper;L" + DH + "core/wrapperInterfaces/world/ILevelWrapper;)V",
		}),
		Map.entry("WorldGenerationQueueMixin", new String[] {
			DH + "core/generation/queues/WorldGenerationQueue#isGeneratorBusy#()Z",
			DH + "core/generation/queues/WorldGenerationQueue#generator#L" + DH + "api/interfaces/override/worldGenerator/IDhApiWorldGenerator;",
			DH + "core/generation/queues/WorldGenerationQueue#inProgressGenTasksByLodPos#Ljava/util/concurrent/ConcurrentHashMap;",
			DH + "core/util/threading/ThreadPoolUtil#getWorldGenExecutor#()L" + DH + "core/util/threading/PriorityTaskPicker$Executor;",
		}),
		Map.entry("GeneratedFullDataSourceProviderMixin", new String[] {
			DH + "core/file/fullDatafile/GeneratedFullDataSourceProvider#worldGenQueueRef#Ljava/util/concurrent/atomic/AtomicReference;",
		}),
		Map.entry("LodQuadTreeMixin", new String[] {
			DH + "core/render/QuadTree/LodQuadTree#updateAllRenderSections#*#" + DH + "core/render/QuadTree/LodQuadTree.threadPoolCanAcceptWorldGenTasks()Z",
			DH + "core/render/QuadTree/LodQuadTree#fullDataSourceProvider#L" + DH + "core/file/fullDatafile/V2/FullDataSourceProviderV2;",
		}),
		Map.entry("ClientBlockStateColorCacheMixin", new String[] {
			DH + "common/wrappers/block/ClientBlockStateColorCache#getColor#(L" + DH + "common/wrappers/block/BiomeWrapper;L" + DH + "core/dataObjects/fullData/sources/FullDataSourceV2;L" + DH + "core/pos/blockPos/DhBlockPos;)I",
			DH + "common/wrappers/block/ClientBlockStateColorCache#clientLevelWrapper#L" + DH + "core/wrapperInterfaces/world/IClientLevelWrapper;",
			DH + "common/wrappers/block/ClientBlockStateColorCache#blockState#Lnet/minecraft/world/level/block/state/BlockState;",
			DH + "common/wrappers/block/ClientBlockStateColorCache#blockStateWrapper#L" + DH + "common/wrappers/block/BlockStateWrapper;",
			DH + "common/wrappers/block/ClientBlockStateColorCache#baseColor#I",
			DH + "common/wrappers/block/ClientBlockStateColorCache#needPostTinting#Z",
			DH + "common/wrappers/block/ClientBlockStateColorCache#tintIndex#I",
		}),
		Map.entry("XaeroMapWriterMixin", new String[] {
			"xaero/map/MapWriter#writeChunk#*#net/minecraft/world/level/Level.getChunk" + MC_GET_CHUNK,
		}),
		Map.entry("XaeroMinimapWriterMixin", new String[] {
			"xaero/common/minimap/write/MinimapWriter#writeTile#*#net/minecraft/client/multiplayer/ClientLevel.getChunk" + MC_GET_CHUNK,
		}),
		Map.entry("XaeroLodTileMixin", new String[] {
			"xaero/map/MapWriter#writeChunk#*#xaero/map/region/MapTileChunk.getTile(II)Lxaero/map/region/MapTile;",
			"xaero/map/region/MapTile#getWorldInterpretationVersion#()I",
		}),
		Map.entry("ModMenuCreditsMixin", new String[] {
			"com/terraformersmc/modmenu/gui/widget/DescriptionListWidget#rebuildUI#*#com/terraformersmc/modmenu/util/mod/Mod.getCredits()Ljava/util/SortedMap;",
			"com/terraformersmc/modmenu/gui/widget/DescriptionListWidget#rebuildUI#*#com/terraformersmc/modmenu/gui/widget/DescriptionListWidget.creditsRoleText(Ljava/lang/String;)Lnet/minecraft/network/chat/Component;",
		}),
		Map.entry("XaeroMinimapLodMixin", new String[] {
			"xaero/common/mods/SupportXaeroWorldmap#prepareMapTexturedRect#(Lorg/joml/Matrix4f;FFIIFFLxaero/map/region/MapTileChunk;"
				+ "Lxaero/common/graphics/renderer/multitexture/MultiTextureRenderTypeRenderer;Lxaero/common/graphics/renderer/multitexture/MultiTextureRenderTypeRenderer;"
				+ "Lxaero/common/minimap/render/MinimapRendererHelper;)V",
			"xaero/map/region/MapTileChunk#getLoadState#()I",
			"xaero/map/region/MapTileChunk#getTile#(II)Lxaero/map/region/MapTile;",
			"xaero/map/region/MapTile#isLoaded#()Z",
			"xaero/map/region/MapTile#getWorldInterpretationVersion#()I",
		})
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
			case "SharedApiMixin" -> true; // dhChunkLodBuilding can be switched off at any time
			case "WorldGenerationQueueMixin", "GeneratedFullDataSourceProviderMixin", "LodQuadTreeMixin" -> true; // so can dhRequestBoost
			case "StructureManagerCaptureMixin" -> config.structures;
			case "ClientBlockStateColorCacheMixin" -> config.bigGlobeColors;
			case "XaeroMapWriterMixin" -> ModCompat.XAERO_WORLD_MAP && ModCompat.VERTIGO && config.vertigoGuard;
			case "XaeroMinimapWriterMixin" -> ModCompat.XAERO_MINIMAP && ModCompat.VERTIGO && config.vertigoGuard;
			case "XaeroLodTileMixin" -> ModCompat.XAERO_WORLD_MAP;
			case "XaeroMinimapLodMixin" -> ModCompat.XAERO_MINIMAP && ModCompat.XAERO_WORLD_MAP; // xaeroMinimapLodMap can be switched at any time
			case "ModMenuCreditsMixin" -> ModCompat.MOD_MENU;
			default -> true;
		};
		if (!wanted) return false;
		String problem = this.problem(name);
		if (problem != null) {
			SKIPPED.put(name, problem);
			String target = name.startsWith("Xaero") ? "Xaero's maps " + ModCompat.version(name.contains("Minimap") ? "xaerominimap" : "xaeroworldmap")
				: name.startsWith("ModMenu") ? "Mod Menu " + ModCompat.version("modmenu")
				: "Distant Horizons " + ModCompat.version("distanthorizons");
			Log.LOGGER.warn("SeedGenGlobe: leaving out {} ({}): {}", name, target, problem);
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
				case "SharedApiMixin" -> "turning off Distant Horizons' LODs from loaded chunks, and the Vertigo guard";
				case "WorldGenerationQueueMixin", "GeneratedFullDataSourceProviderMixin", "LodQuadTreeMixin" -> "letting Distant Horizons hand SeedGenGlobe more sections at once (dhRequestBoost)";
				case "StructureManagerCaptureMixin" -> "keeping LOD structures from asking the real level about structures";
				case "ClientBlockStateColorCacheMixin" -> "Big Globe colours for LODs";
				case "XaeroMapWriterMixin", "XaeroMinimapWriterMixin" -> "keeping Vertigo's partly synced chunks off Xaero's map";
				case "XaeroLodTileMixin" -> "Xaero mapping real chunks over LOD tiles right away";
				case "XaeroMinimapLodMixin" -> "hiding the LOD map from the minimap (xaeroMinimapLodMap=false)";
				case "ModMenuCreditsMixin" -> "the short credits line in Mod Menu";
				default -> entry.getKey();
			};
			String mod = entry.getKey().startsWith("XaeroMinimap") ? "Xaero's Minimap" : entry.getKey().startsWith("Xaero") ? "Xaero's World Map" : entry.getKey().startsWith("StructureManager") ? "Minecraft" : entry.getKey().startsWith("ModMenu") ? "Mod Menu" : "Distant Horizons";
			lines.add("Not available with this " + mod + " build: " + feature + " (" + entry.getValue() + ")");
		}
		return lines;
	}

	private String problem(String mixinName) {
		String[] requirements = REQUIREMENTS.get(mixinName);
		if (requirements == null) return null;
		for (String requirement : requirements) {
			String[] parts = requirement.split("#", 4);
			ClassModel model = this.read(parts[0]);
			if (model == null) return "class " + parts[0] + " not found";
			if (parts.length == 4) {
				if (!calls(model, parts[1], parts[3])) return parts[0] + "." + parts[1] + " no longer calls " + parts[3];
			}
			else if (!has(model, parts[1], parts[2])) return "missing " + parts[1] + " " + parts[2] + " in " + parts[0];
		}
		return null;
	}

	/** Whether a method of this name contains a call "Owner.name(descriptor)". */
	private static boolean calls(ClassModel model, String methodName, String call) {
		int dot = call.indexOf('.'), paren = call.indexOf('(');
		if (dot < 0 || paren < dot) return false;
		String owner = call.substring(0, dot), name = call.substring(dot + 1, paren), descriptor = call.substring(paren);
		for (MethodModel method : model.methods()) {
			if (!method.methodName().equalsString(methodName)) continue;
			java.util.Optional<java.lang.classfile.CodeModel> code = method.code();
			if (code.isEmpty()) continue;
			for (java.lang.classfile.CodeElement element : code.get()) {
				if (element instanceof java.lang.classfile.instruction.InvokeInstruction invoke
					&& invoke.owner().asInternalName().equals(owner)
					&& invoke.name().equalsString(name)
					&& invoke.type().equalsString(descriptor)) {
					return true;
				}
			}
		}
		return false;
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
