package dev.copperworks.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.copperworks.config.Settings;
import java.io.File;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Логика опыта и черт без сервера: Settings строится из config.yml, а Traits — чистые функции.
 * group-filters убираем, а проверку upgrade-item подменяем: для них нужен реестр живого сервера.
 */
class TraitsTest {
    private static final int ROLLS = 4000;

    private static YamlConfiguration defaults() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        config.set("jobs.sorter.group-filters", List.of());
        return config;
    }

    private static Settings settings(Map<String, Object> overrides) {
        YamlConfiguration config = defaults();
        overrides.forEach(config::set);
        return new Settings(config, material -> true);
    }

    private static Settings settings() {
        return settings(Map.of());
    }

    @Test
    void defaultCurveMatchesDocs() {
        Settings s = settings();
        long[] expected = {100, 283, 520, 800, 1118, 1470, 1852, 2263, 2700};
        long total = 0;
        for (int level = 1; level < s.maxLevel; level++) {
            assertEquals(expected[level - 1], s.xpToNext(level), "опыт для уровня " + level);
            total += s.xpToNext(level);
        }
        assertEquals(11106, total, "всего опыта до 10 уровня (README и config.yml говорят «около 11 100»)");
    }

    @Test
    void codeDefaultsMatchConfigYml() {
        // Если секций нет в config.yml сервера, значения по умолчанию из кода должны совпасть со встроенным файлом.
        Settings file = settings();
        Settings empty = new Settings(new YamlConfiguration(), material -> true);
        assertEquals(file.xpEnabled, empty.xpEnabled);
        assertEquals(file.maxLevel, empty.maxLevel);
        assertEquals(file.xpMaxPerHour, empty.xpMaxPerHour);
        assertEquals(file.xpSorterPerItem, empty.xpSorterPerItem);
        assertEquals(file.xpCourierPerItem, empty.xpCourierPerItem);
        assertEquals(file.xpCourierTripBase, empty.xpCourierTripBase);
        assertEquals(file.xpCourierTripPerBlock, empty.xpCourierTripPerBlock);
        assertEquals(file.xpFarmerPerCrop, empty.xpFarmerPerCrop);
        assertEquals(file.traitLevels, empty.traitLevels);
        for (int level = 1; level < file.maxLevel; level++) {
            assertEquals(file.xpToNext(level), empty.xpToNext(level), "кривая, уровень " + level);
        }
        assertEquals(file.traitsEnabled, empty.traitsEnabled);
        assertEquals(file.maxTraits, empty.maxTraits);
        assertEquals(file.rerollEnabled, empty.rerollEnabled);
        assertEquals(file.rerollCost, empty.rerollCost);
        assertEquals(file.swiftTripTime, empty.swiftTripTime);
        assertEquals(file.porterCourierStacks, empty.porterCourierStacks);
        assertEquals(file.porterSorterItems, empty.porterSorterItems);
        assertEquals(file.tidyRescan, empty.tidyRescan);
        assertEquals(file.greedyChance, empty.greedyChance);
        assertEquals(file.tirelessPause, empty.tirelessPause);
        assertEquals(file.stainlessPenalty, empty.stainlessPenalty);
        assertEquals(file.farsightedRadius, empty.farsightedRadius);
        assertEquals(file.lazySpeed, empty.lazySpeed);
        assertEquals(file.lazyCapacityLevels, empty.lazyCapacityLevels);
        assertEquals(file.courierFrameFilter, empty.courierFrameFilter);
        for (Trait trait : Trait.values()) {
            assertEquals(file.traitEnabled(trait), empty.traitEnabled(trait), "enabled " + trait.id());
        }
    }

    @Test
    void everyTraitHasConfigSection() {
        YamlConfiguration config = defaults();
        for (Trait trait : Trait.values()) {
            assertTrue(config.isConfigurationSection("traits." + trait.id()), "нет секции traits." + trait.id());
            assertTrue(config.contains("traits." + trait.id() + ".weight"), "нет веса у traits." + trait.id());
        }
    }

    @Test
    void traitLevelsAreSortedDistinctAndInRange() {
        Settings s = settings(Map.of("experience.trait-levels", List.of(9, 3, 3, 1, 50, 6)));
        assertEquals(List.of(3, 6, 9), s.traitLevels);
    }

    @Test
    void owedAndNextTraitLevel() {
        Settings s = settings();
        assertEquals(0, Traits.owed(s, 2));
        assertEquals(1, Traits.owed(s, 3));
        assertEquals(2, Traits.owed(s, 8));
        assertEquals(3, Traits.owed(s, 10));
        assertEquals(3, Traits.nextTraitLevel(s, 1, 0));
        assertEquals(6, Traits.nextTraitLevel(s, 3, 1));
        assertNull(Traits.nextTraitLevel(s, 9, 3), "все черты уже выданы");
        assertNull(Traits.nextTraitLevel(s, 10, 2), "на максимальном уровне следующей черты нет");

        Settings two = settings(Map.of("traits.max-per-golem", 2));
        assertEquals(2, Traits.owed(two, 10));
        assertNull(Traits.nextTraitLevel(two, 6, 2));

        Settings off = settings(Map.of("traits.enabled", false));
        assertEquals(0, Traits.owed(off, 10));
        assertNull(Traits.nextTraitLevel(off, 1, 0));
    }

    @Test
    void rollNeverRepeatsOrConflicts() {
        Settings s = settings();
        for (int i = 0; i < ROLLS; i++) {
            Trait trait = Traits.roll(s, List.of(Trait.LAZY), Trait.TIDY);
            assertNotNull(trait);
            assertNotEquals(Trait.LAZY, trait, "повтор");
            assertNotEquals(Trait.TIDY, trait, "исключённая черта (переобучение)");
            assertFalse(trait.conflictsWith(Trait.LAZY), "несовместимо с «Ленивым»: " + trait.id());
        }
        for (int i = 0; i < ROLLS; i++) {
            assertNotEquals(Trait.LAZY, Traits.roll(s, List.of(Trait.SWIFT), null), "«Ленивый» к «Быстроногому»");
        }
    }

    @Test
    void rollSkipsDisabledAndZeroWeight() {
        Settings s = settings(Map.of("traits.swift.enabled", false, "traits.porter.weight", 0));
        assertFalse(s.traitObtainable(Trait.SWIFT));
        assertFalse(s.traitObtainable(Trait.PORTER));
        assertFalse(s.traitEnabled(Trait.SWIFT), "выключенная черта не действует");
        assertTrue(s.traitEnabled(Trait.PORTER), "вес 0 — не выпадает, но действует");
        for (int i = 0; i < ROLLS; i++) {
            Trait trait = Traits.roll(s, List.of(), null);
            assertNotEquals(Trait.SWIFT, trait);
            assertNotEquals(Trait.PORTER, trait);
        }
    }

    @Test
    void rollReturnsNullWhenPoolIsEmpty() {
        assertNull(Traits.roll(settings(Map.of("traits.enabled", false)), List.of(), null));
        // Остались «Неутомимый» и «Ленивый», но «Ленивый» несовместим с «Быстроногим».
        List<Trait> owned = List.of(Trait.SWIFT, Trait.PORTER, Trait.TIDY, Trait.GREEDY, Trait.STAINLESS, Trait.FARSIGHTED);
        assertEquals(Trait.TIRELESS, Traits.roll(settings(), owned, null));
        List<Trait> all = List.of(Trait.SWIFT, Trait.PORTER, Trait.TIDY, Trait.GREEDY, Trait.TIRELESS, Trait.STAINLESS,
            Trait.FARSIGHTED);
        assertNull(Traits.roll(settings(), all, null));
    }

    @Test
    void rollFollowsWeights() {
        Map<String, Object> overrides = new HashMap<>();
        for (Trait trait : Trait.values()) {
            overrides.put("traits." + trait.id() + ".weight", 0);
        }
        overrides.put("traits.swift.weight", 1);
        overrides.put("traits.porter.weight", 3);
        Settings s = settings(overrides);
        Map<Trait, Integer> counts = new EnumMap<>(Trait.class);
        int rolls = 20_000;
        for (int i = 0; i < rolls; i++) {
            counts.merge(Traits.roll(s, List.of(), null), 1, Integer::sum);
        }
        assertEquals(Set.of(Trait.SWIFT, Trait.PORTER), counts.keySet());
        double porterShare = counts.get(Trait.PORTER) / (double) rolls;
        assertEquals(0.75, porterShare, 0.03, "доля «Грузчика» при весах 1:3");
    }

    @Test
    void conflictsAreSymmetricAndIdsUnique() {
        Set<String> ids = new HashSet<>();
        for (Trait a : Trait.values()) {
            assertTrue(ids.add(a.id()), "повтор id " + a.id());
            assertEquals(a, Trait.byId(a.id()));
            assertFalse(a.conflictsWith(a));
            for (Trait b : Trait.values()) {
                assertEquals(a.conflictsWith(b), b.conflictsWith(a), a.id() + " / " + b.id());
            }
        }
        assertTrue(Trait.LAZY.conflictsWith(Trait.SWIFT));
        assertTrue(Trait.TIRELESS.conflictsWith(Trait.LAZY));
        assertNull(Trait.byId("unknown"));
    }

    @Test
    void valueAtExtrapolatesPastLastLevel() {
        assertEquals(6, Traits.valueAt(new int[]{1, 3, 6, 9}, 2));
        assertEquals(12, Traits.valueAt(new int[]{1, 3, 6, 9}, 4), "«Ленивый» на III уровне курьера");
        assertEquals(15, Traits.valueAt(new int[]{1, 3, 6, 9}, 5));
        assertEquals(96, Traits.valueAt(new int[]{8, 16, 32, 64}, 4));
        assertEquals(5, Traits.valueAt(new int[]{5}, 3), "одно значение — без роста");
        assertEquals(9, Traits.valueAt(new int[]{9, 9, 9, 9}, 6), "плоский список не растёт");
    }

    @Test
    void scaledIntervalAppliesPauseAndFloor() {
        Settings s = settings();
        assertEquals(40, s.scaledInterval(40, 1.0, 1.0));
        assertEquals(34, s.scaledInterval(40, 1.0, 0.85), "«Неутомимый» −15%");
        assertEquals(16, s.scaledInterval(40, 2.5, 1.0));
        assertEquals(10, s.scaledInterval(10, 5.0, 0.5), "не меньше 10 тиков");
    }
}
