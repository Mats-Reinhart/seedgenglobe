package dev.seedgenglobe.dh;

/** Added to Distant Horizons' world generation queue by {@code WorldGenerationQueueMixin}. */
public interface DhQueueHook {

	/** Whether this queue hands its sections to SeedGenGlobe's generator. */
	boolean seedgenglobe$generatedBySeedGenGlobe();
}
