package dev.seedgenglobe.dh;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;

import dev.seedgenglobe.command.GlobeCommands;
import dev.seedgenglobe.terrain.ColumnProfile;
import dev.seedgenglobe.terrain.SectionSampler;

/** Diagnostics used by {@code /seedgenglobe}. */
public final class DiagnosticsBridge {

	private DiagnosticsBridge() {}

	public static List<String> statusLines() {
		List<String> lines = new ArrayList<>();
		List<GlobeWorldGenerator> generators = DhBridge.serverGenerators();
		for (GlobeWorldGenerator generator : generators) {
			lines.add(generator.stats.name + ": " + generator.stats.summary());
			if (generator.structures() != null) lines.addAll(generator.structures().statusLines());
		}
		if (!generators.isEmpty()) {
			lines.add("Sections run on " + GeneratorThreads.describe() + ".");
			String pipeline = DhPipeline.describe(GeneratorThreads.ownThreads());
			if (pipeline != null) lines.add(pipeline);
		}
		String advice = generators.isEmpty() ? null : DhBridge.nSizedAdvice();
		if (advice != null) lines.add("Note: " + advice);
		if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
			try {
				lines.addAll(dev.seedgenglobe.client.ClientGeneration.statusLines());
				if (dev.seedgenglobe.client.VertigoGuard.enabled()) {
					lines.add(dev.seedgenglobe.client.VertigoGuard.status());
				}
			}
			catch (LinkageError error) {
				lines.add("Client diagnostics unavailable: " + error);
			}
		}
		return lines;
	}

	public static @Nullable GlobeCommands.DhAccess.Probe probeFor(ServerLevel level) {
		for (GlobeWorldGenerator generator : DhBridge.serverGenerators()) {
			if (generator.level.getWrappedMcObject() == level && !generator.isClosed()) {
				return new GeneratorProbe(generator);
			}
		}
		return null;
	}

	static final class GeneratorProbe implements GlobeCommands.DhAccess.Probe {

		final GlobeWorldGenerator generator;
		final SectionSampler sampler;

		GeneratorProbe(GlobeWorldGenerator generator) {
			this.generator = generator;
			this.sampler = generator.newSampler();
		}

		@Override
		public synchronized int surfaceTop(int x, int z) {
			return this.column(x, z).surfaceTop;
		}

		@Override
		public synchronized ColumnProfile column(int x, int z) {
			return this.sampler.probe(x, z, this.generator.levelMinY(), this.generator.levelMaxY(), this.generator.topSkyLight());
		}

		@Override
		public @Nullable List<String> structures(int x, int z, int radius, int tier, int limit) {
			if (this.generator.structures() == null) return null;
			return this.generator.structures().describe(x, z, radius, tier, limit);
		}

		@Override
		public int structureDetail() {
			return this.generator.structures() != null ? this.generator.structures().fullDetail() : -1;
		}

		@Override
		public synchronized String benchmark(int x, int z, int sections, int detail) {
			CountingDataSource sink = new CountingDataSource();
			int span = SectionSampler.WIDTH << detail;
			int side = (int)(Math.ceil(Math.sqrt(sections)));
			int originX = Math.floorDiv(x, span) * span - (side / 2) * span;
			int originZ = Math.floorDiv(z, span) * span - (side / 2) * span;
			long start = System.nanoTime();
			int done = 0;
			outer:
			for (int sz = 0; sz < side; sz++) {
				for (int sx = 0; sx < side; sx++) {
					if (done == sections) break outer;
					this.generator.generateNow(originX + sx * span, originZ + sz * span, (byte)(detail), sink, false);
					done++;
				}
			}
			double seconds = (System.nanoTime() - start) / 1.0e9D;
			long blocks = (long)(span) * span * done;
			long chunks = Math.max(blocks / 256L, 1L);
			return String.format(
				Locale.ROOT,
				"detail %d: %d sections (%d x %d blocks each, %,d chunks of terrain) in %.2f s on one thread = %.1f ms/section, %,.0f chunk-areas/s, %.1f data points per column",
				detail,
				done,
				span,
				span,
				chunks,
				seconds,
				1000.0D * seconds / Math.max(done, 1),
				chunks / Math.max(seconds, 1.0e-6D),
				sink.points / (double)(Math.max(sink.columns, 1L))
			);
		}
	}

	/** Accepts columns and only counts them. */
	static final class CountingDataSource implements IDhApiFullDataSource {

		long columns, points;

		@Override
		public int getWidthInDataColumns() {
			return SectionSampler.WIDTH;
		}

		@Override
		public List<DhApiTerrainDataPoint> setApiDataPointColumn(int relX, int relZ, List<DhApiTerrainDataPoint> dataPoints) {
			this.columns++;
			this.points += dataPoints.size();
			return dataPoints;
		}

		@Override
		public List<DhApiTerrainDataPoint> getApiDataPointColumn(int relX, int relZ) {
			return List.of();
		}
	}
}
