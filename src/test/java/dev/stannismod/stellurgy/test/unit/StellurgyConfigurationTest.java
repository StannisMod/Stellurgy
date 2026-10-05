package dev.stannismod.stellurgy.test.unit;

import org.junit.Test;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * The configuration's copy constructor.
 *
 * Pure construction tests — not exercising loadPreInit (that depends on Forge
 * Configuration files and the mod loader).
 */
public class StellurgyConfigurationTest {

    @Test
    public void cloneConstructorCopiesFields() {
        StellurgyConfiguration src = new StellurgyConfiguration();
        src.stationClearanceHeight = 4242;
        src.stationSize = 256;

        StellurgyConfiguration copy = new StellurgyConfiguration(src);
        assertEquals(4242, copy.stationClearanceHeight);
        assertEquals(256, copy.stationSize);
    }

    /**
     * Performance section default-stability check.
     * MED batch pack 4 — C017 regression guard.
     *
     * <p>Contract: the copy constructor must yield collection fields that are
     * independent containers, not references aliasing the source config's
     * Map/List/Set. The old {@code field.getClass().isAssignableFrom(Map.class)}
     * test was always false ({@code getClass()} is {@code java.lang.reflect.Field}),
     * so the deep-copy branch was dead and every {@code @ConfigProperty} collection
     * was shallow-copied by reference — a correctness landmine for the
     * server→client {@code PacketConfigSync} copy. This pins container
     * independence for the collection kinds the config holds (List, Map).</p>
     */
    @Test
    public void cloneConstructorGivesIndependentCollections() {
        StellurgyConfiguration src = new StellurgyConfiguration();
        src.laserBlackListDims.add(42);              // List
        src.blackHoleGeneratorBlocks.put(null, 7);   // Map

        StellurgyConfiguration copy = new StellurgyConfiguration(src);

        assertNotSame("copy must own an independent list (C017)",
                src.laserBlackListDims, copy.laserBlackListDims);
        assertNotSame("copy must own an independent map (C017)",
                src.blackHoleGeneratorBlocks, copy.blackHoleGeneratorBlocks);

        // Mutating the copy must not leak into the source.
        copy.laserBlackListDims.add(99);
        copy.blackHoleGeneratorBlocks.clear();
        assertEquals("mutating the copy's list must not touch the source",
                1, src.laserBlackListDims.size());
        assertEquals("mutating the copy's map must not touch the source",
                1, src.blackHoleGeneratorBlocks.size());

        // Contents must still be carried over by the copy.
        assertTrue("copy must contain the source's list element",
                copy.laserBlackListDims.contains(42));
        assertEquals("copy must contain the source's map entry",
                Integer.valueOf(7), new StellurgyConfiguration(src).blackHoleGeneratorBlocks.get(null));
    }

    /**
     * The PERFORMANCE config section in {@link StellurgyConfiguration#loadPreInit} sets
     * {@code atmosphereHandleBitMask} and {@code oxygenVentSize}; they are
     * {@code @ConfigProperty} tagged, so loadPreInit->sync->clone is the production path and the
     * clone must carry them end-to-end.
     */
    @Test
    public void performanceConfigDefaultsStable() {
        StellurgyConfiguration cfg = new StellurgyConfiguration();

        cfg.atmosphereHandleBitMask = 3;
        cfg.oxygenVentSize = 32;
        StellurgyConfiguration copy = new StellurgyConfiguration(cfg);
        assertEquals(3, copy.atmosphereHandleBitMask);
        assertEquals(32, copy.oxygenVentSize);
    }

    /**
     * Cloning a partially-populated config must preserve every mutation, even
     * ones loadPreInit would never have set.
     */
    @Test
    public void unknownConfigDoesNotCrash() {
        StellurgyConfiguration cfg = new StellurgyConfiguration();

        cfg.stationClearanceHeight = -777;     // sentinel-out-of-range value
        cfg.spaceLaserPowerMult = Float.NaN;   // pathological float
        StellurgyConfiguration clone = new StellurgyConfiguration(cfg);
        assertEquals(-777, clone.stationClearanceHeight);
        assertTrue("NaN must survive clone (no silent normalisation)",
                Float.isNaN(clone.spaceLaserPowerMult));
    }

    /**
     * A field the config file can set is a field the reflective machinery must be able to see.
     *
     * <p>`@ConfigProperty` is not decoration: the copy constructor copies exactly the annotated
     * fields, and `needsSync` decides what crosses to a client. A field assigned from
     * {@code config.get(...)} and left unannotated is loaded from disk and then invisible to
     * everything else — it silently reverts to its class default in every copy, and no compiler,
     * no config reload and no test that reads the singleton can tell.</p>
     *
     * <p>Found twice: {@code shotPenetrationSpeedFloor} sitting directly under its annotated twin
     * {@code shotReflectionSpeedFloor}, and {@code aluminumPerChunk} between two annotated
     * neighbours. Both look right in a diff, which is the whole problem.</p>
     *
     * <p><b>What it cannot see.</b> It reads the source text of {@code StellurgyConfiguration.java}
     * rather than the loader at runtime, so a key set anywhere else is invisible; and it says
     * nothing about whether {@code needsSync} is set CORRECTLY — only that the field is annotated
     * at all.</p>
     */
    @Test
    public void everyFieldTheConfigFileSetsIsVisibleToTheReflectiveMachinery() throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src", "main", "java", "dev", "stannismod", "stellurgy", "api",
                "StellurgyConfiguration.java")), java.nio.charset.StandardCharsets.UTF_8);

        java.util.Set<String> assigned = new java.util.TreeSet<String>();
        java.util.regex.Matcher a =
                java.util.regex.Pattern.compile("\\bstellurgyConfig\\.(\\w+)\\s*=").matcher(source);
        while (a.find()) {
            assigned.add(a.group(1));
        }
        assertTrue("the loader scan matched nothing, so this test is measuring nothing",
                assigned.size() > 100);

        java.util.Set<String> unannotated = new java.util.TreeSet<String>(assigned);
        java.util.regex.Matcher f = java.util.regex.Pattern.compile(
                "@ConfigProperty[^\\n]*\\n(?:\\s*@\\w+[^\\n]*\\n)*\\s*public\\s+[\\w<>,\\[\\]. ]+?\\s+(\\w+)\\s*[=;]")
                .matcher(source);
        while (f.find()) {
            unannotated.remove(f.group(1));
        }
        unannotated.removeAll(SERVER_ONLY_BY_DESIGN.keySet());

        assertTrue("these fields are loaded from the config file and carry no @ConfigProperty, so "
                + "the copy constructor drops them and they revert to their class default in every "
                + "copy: " + unannotated, unannotated.isEmpty());
    }

    /**
     * Fields deliberately outside the reflective machinery. Every entry needs a reason that is
     * also written at the declaration; "it fails otherwise" is not one.
     *
     * <p>A constant: an unmodifiable map of strings, built once by {@link #serverOnlyByDesign()}.</p>
     */
    private static final java.util.Map<String, String> SERVER_ONLY_BY_DESIGN = serverOnlyByDesign();

    private static java.util.Map<String, String> serverOnlyByDesign() {
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<String, String>();
        String reason = "movable-ship space subsystem — server-authoritative, loaded in "
                + "loadPreInit, deliberately never network-synced (stated at the declaration)";
        for (String name : new String[]{"enableSpaceSubsystem", "spaceCellPoolSize",
                "spaceCellGcPolicy", "spaceCellMaxAgeTicks", "spaceMaxStoredCells",
                "spaceHomeSystemCoord", "spaceTransitOfflineProgress"}) {
            fields.put(name, reason);
        }
        return java.util.Collections.unmodifiableMap(fields);
    }
}
