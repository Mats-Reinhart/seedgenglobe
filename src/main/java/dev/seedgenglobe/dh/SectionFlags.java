package dev.seedgenglobe.dh;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;

import dev.seedgenglobe.GlobeConfig;
import dev.seedgenglobe.SeedGenGlobe;

/**
 * Stops generated sections from being merged into their coarser parent sections.
 * <p>
 * Distant Horizons flags every section an API generator returns with {@code applyToParent}, so its update
 * propagator rewrites one quarter of the parent with a 2 x 2 downsample of the new section, then a quarter of the
 * grandparent, and so on up to detail level 15. A parent that did not exist yet is created holding only that
 * quarter. The render tree treats such a partial parent as renderable: when the camera wants that coarser level it
 * draws the parent with three empty quarters, and when it wants the finer level but one child is still missing it
 * hides the three finished children behind the partial parent. While the propagator lags behind generation (which
 * is most of the time on a large render distance) those empty quarters are the black holes that disappear "once
 * everything is done". Skipping the propagation also saves several database rewrites per generated section. Chunk
 * updates from real terrain keep Distant Horizons' normal behaviour.
 * <p>
 * Without the propagation a parent does not exist at all unless it is generated itself, and Distant Horizons only
 * asks for the sections it wants at their own level, while it draws the parents in place of children that are still
 * loading. A parent with no data counts as drawable too, so up to 1.3.2 moving around showed holes the size of whole
 * parents. Since 1.3.3 SeedGenGlobe has those parents generated directly ({@code generateParentSections}) and keeps
 * empty ones from hiding their children (see {@link StandIns}).
 * <p>
 * {@code applyToParent} is a field of Distant Horizons' internal data source class, not of its API, so this is
 * guarded: if the field ever changes, generation carries on with Distant Horizons' default.
 */
public final class SectionFlags {

	private static volatile boolean broken;

	private SectionFlags() {}

	/** @param dataSource the data source Distant Horizons passed to the generator (an API interface) */
	public static void keepToItself(Object dataSource) {
		if (broken || GlobeConfig.get().updateParentSections) return;
		try {
			if (dataSource instanceof FullDataSourceV2 source) {
				source.applyToParent = Boolean.FALSE;
			}
		}
		catch (LinkageError error) {
			broken = true;
			SeedGenGlobe.LOGGER.warn("SeedGenGlobe: this Distant Horizons build has no applyToParent flag; generated sections will update their parents as usual.", error);
		}
	}
}
