package dev.seedgenglobe.xaero;

import dev.seedgenglobe.dh.GlobeWorldGenerator;
import dev.seedgenglobe.terrain.SampledColumns;

/**
 * Reads every section SeedGenGlobe generates for Distant Horizons into map pixels, on the generating thread (a few
 * milliseconds next to the hundreds the section took), and hands them to the {@link LodMapBuilder}. Registered on the
 * generator of the dimension the map shows, for as long as it shows it.
 */
final class MapFeed implements GlobeWorldGenerator.SectionListener {

	final LodMapBuilder builder;
	final LodMapBuilder.Target target;

	MapFeed(LodMapBuilder builder, LodMapBuilder.Target target) {
		this.builder = builder;
		this.target = target;
	}

	@Override
	public void sectionGenerated(GlobeWorldGenerator generator, int originX, int originZ, int detail, SampledColumns columns) {
		// coarser than the map ever computes (Distant Horizons' far parent sections): the map has finer data there or
		// computes it itself, and taking these first would have Xaero's map filled twice
		if (detail > LodMapBuilder.MAX_DETAIL) return;
		if (!this.builder.wants(this.target, originX, originZ, detail)) return;
		MapSection section = new MapSection(this.target, detail, originX, originZ, true);
		section.fill(columns, this.target.rules());
		this.builder.offer(section);
	}
}
