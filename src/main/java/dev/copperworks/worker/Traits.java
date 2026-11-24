package dev.copperworks.worker;

import dev.copperworks.config.Messages;
import dev.copperworks.config.Settings;
import dev.copperworks.util.Format;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jspecify.annotations.Nullable;

/**
 * Единственное место, где черты влияют на числа. Профессии и меню берут отсюда итоговые
 * скорость, паузы, вместимость и радиусы, а не читают Settings напрямую.
 */
public final class Traits {
    private Traits() {
    }

    /** Черта есть у голема, включена в конфиге и действует в его текущей профессии. */
    public static boolean active(Settings s, Worker worker, Trait trait) {
        return s.traitEnabled(trait) && trait.appliesTo(worker.job()) && worker.traits().contains(trait);
    }

    // ── Скорость и паузы ──────────────────────────────────

    /** Итоговый множитель скорости: уровень «Скорости» × окисление («Нержавейка») × «Ленивый». */
    public static double speedFactor(Settings s, Worker worker) {
        double oxidation = s.oxidationFactor(worker.golem().getWeatheringState());
        if (oxidation < 1.0 && active(s, worker, Trait.STAINLESS)) {
            oxidation = 1.0 - (1.0 - oxidation) * s.stainlessPenalty;
        }
        double factor = (1.0 + s.speedPerLevel * worker.level(Upgrade.SPEED)) * oxidation;
        if (active(s, worker, Trait.LAZY)) {
            factor *= s.lazySpeed;
        }
        return factor;
    }

    /** Пауза между действиями профессии с учётом скорости и «Неутомимого». */
    public static int interval(Settings s, Worker worker, int baseTicks) {
        double pause = active(s, worker, Trait.TIRELESS) ? s.tirelessPause : 1.0;
        return s.scaledInterval(baseTicks, speedFactor(s, worker), pause);
    }

    /** Длительность плеча рейса курьера («Быстроногий»). */
    public static long tripLegMillis(Settings s, Worker worker, long baseMillis) {
        if (!active(s, worker, Trait.SWIFT)) {
            return baseMillis;
        }
        return Math.max(1000L, Math.round(baseMillis * s.swiftTripTime));
    }

    // ── Вместимость ───────────────────────────────────────

    /** Уровень, по которому считается вместимость: «Ленивый» поднимает его выше улучшения. */
    public static int capacityLevel(Settings s, Worker worker, int upgradeLevel) {
        return upgradeLevel + (active(s, worker, Trait.LAZY) ? s.lazyCapacityLevels : 0);
    }

    public static int sorterItemsPerMove(Settings s, Worker worker) {
        return sorterItemsPerMove(s, worker, worker.level(Upgrade.CAPACITY));
    }

    public static int sorterItemsPerMove(Settings s, Worker worker, int upgradeLevel) {
        double items = valueAt(s.sorterItemsPerMove, capacityLevel(s, worker, upgradeLevel));
        if (active(s, worker, Trait.PORTER)) {
            items *= s.porterSorterItems;
        }
        return Math.max(1, (int) Math.round(items));
    }

    public static int courierStacks(Settings s, Worker worker) {
        return courierStacks(s, worker, worker.level(Upgrade.CAPACITY));
    }

    public static int courierStacks(Settings s, Worker worker, int upgradeLevel) {
        int stacks = valueAt(s.courierStacks, capacityLevel(s, worker, upgradeLevel));
        if (active(s, worker, Trait.PORTER)) {
            stacks += s.porterCourierStacks;
        }
        return Math.clamp(stacks, 1, 27);
    }

    public static int farmerCropsPerAction(Settings s, Worker worker) {
        return farmerCropsPerAction(s, worker, worker.level(Upgrade.CAPACITY));
    }

    public static int farmerCropsPerAction(Settings s, Worker worker, int upgradeLevel) {
        return Math.clamp(valueAt(s.farmerCropsPerAction, capacityLevel(s, worker, upgradeLevel)), 1, 64);
    }

    // ── Радиусы и сортировка ──────────────────────────────

    public static int sorterRadius(Settings s, Worker worker) {
        return sorterRadius(s, worker, worker.level(Upgrade.RANGE));
    }

    public static int sorterRadius(Settings s, Worker worker, int upgradeLevel) {
        return s.sorterRadius[Math.clamp(upgradeLevel, 0, Upgrade.MAX_LEVEL)] + farsighted(s, worker);
    }

    public static int farmerRadius(Settings s, Worker worker) {
        return farmerRadius(s, worker, worker.level(Upgrade.RANGE));
    }

    public static int farmerRadius(Settings s, Worker worker, int upgradeLevel) {
        return s.farmerRadius[Math.clamp(upgradeLevel, 0, Upgrade.MAX_LEVEL)] + farsighted(s, worker);
    }

    private static int farsighted(Settings s, Worker worker) {
        return active(s, worker, Trait.FARSIGHTED) ? s.farsightedRadius : 0;
    }

    /** Как часто сортировщик пересканирует сундуки вокруг («Аккуратный» — чаще). */
    public static int sorterRescanTicks(Settings s, Worker worker, int baseTicks) {
        if (!active(s, worker, Trait.TIDY)) {
            return baseTicks;
        }
        return Math.max(20, (int) Math.round(baseTicks * s.tidyRescan));
    }

    /** «Аккуратный» сначала раскладывает предметы, для которых есть рамка-фильтр. */
    public static boolean sorterPrefersFilters(Settings s, Worker worker) {
        return active(s, worker, Trait.TIDY);
    }

    /** «Жадный»: выпал ли бонусный предмет с этого растения. */
    public static boolean rollExtraDrop(Settings s, Worker worker) {
        return active(s, worker, Trait.GREEDY) && ThreadLocalRandom.current().nextDouble() < s.greedyChance;
    }

    // ── Выдача черт ───────────────────────────────────────

    /** Сколько черт положено голему на этом уровне. */
    public static int owed(Settings s, int level) {
        if (!s.traitsEnabled) {
            return 0;
        }
        int count = 0;
        for (int traitLevel : s.traitLevels) {
            if (traitLevel <= level) {
                count++;
            }
        }
        return Math.min(count, s.maxTraits);
    }

    /** Уровень, на котором голем получит следующую черту, или null, если больше не положено. */
    public static @Nullable Integer nextTraitLevel(Settings s, int level, int owned) {
        if (!s.traitsEnabled || owned >= s.maxTraits || level >= s.maxLevel) {
            return null;
        }
        for (int traitLevel : s.traitLevels) {
            if (traitLevel > level && owed(s, traitLevel) > owned) {
                return traitLevel;
            }
        }
        return null;
    }

    /**
     * Случайная черта из взвешенного пула: без повторов, без взаимоисключающих пар
     * с уже имеющимися и не равная exclude (старая черта при переобучении).
     *
     * @return null, если подходящих черт нет
     */
    public static @Nullable Trait roll(Settings s, Collection<Trait> owned, @Nullable Trait exclude) {
        List<Trait> pool = new ArrayList<>();
        int total = 0;
        for (Trait trait : Trait.values()) {
            if (trait == exclude || owned.contains(trait) || !s.traitObtainable(trait)) {
                continue;
            }
            if (owned.stream().anyMatch(trait::conflictsWith)) {
                continue;
            }
            pool.add(trait);
            total += s.traitWeight(trait);
        }
        if (pool.isEmpty()) {
            return null;
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (Trait trait : pool) {
            roll -= s.traitWeight(trait);
            if (roll < 0) {
                return trait;
            }
        }
        return pool.getLast();
    }

    // ── Тексты ────────────────────────────────────────────

    public static Component name(Messages m, Trait trait) {
        return m.get("trait." + trait.id() + ".name");
    }

    /** Описание черты с числами из config.yml. */
    public static Component description(Messages m, Settings s, Trait trait) {
        return switch (trait) {
            case SWIFT -> m.get("trait.swift.desc",
                Placeholder.unparsed("percent", percent(1.0 - s.swiftTripTime)));
            case PORTER -> m.get("trait.porter.desc",
                Placeholder.unparsed("stacks", String.valueOf(s.porterCourierStacks)),
                Placeholder.unparsed("percent", percent(s.porterSorterItems - 1.0)));
            case TIDY -> m.get("trait.tidy.desc",
                Placeholder.unparsed("times", Format.decimal(1.0 / s.tidyRescan)));
            case GREEDY -> m.get("trait.greedy.desc",
                Placeholder.unparsed("chance", percent(s.greedyChance)));
            case TIRELESS -> m.get("trait.tireless.desc",
                Placeholder.unparsed("percent", percent(1.0 - s.tirelessPause)));
            case STAINLESS -> m.get("trait.stainless.desc",
                Placeholder.unparsed("percent", percent(1.0 - s.stainlessPenalty)));
            case FARSIGHTED -> m.get("trait.farsighted.desc",
                Placeholder.unparsed("blocks", String.valueOf(s.farsightedRadius)));
            case LAZY -> m.get("trait.lazy.desc",
                Placeholder.unparsed("percent", percent(1.0 - s.lazySpeed)),
                Placeholder.unparsed("levels", String.valueOf(s.lazyCapacityLevels)));
        };
    }

    private static String percent(double fraction) {
        return String.valueOf(Math.round(fraction * 100));
    }

    /** Значение по уровню; за пределами массива продолжаем последний шаг (нужно «Ленивому» на III уровне). */
    static int valueAt(int[] values, int level) {
        if (level < values.length) {
            return values[Math.max(0, level)];
        }
        int last = values[values.length - 1];
        int step = values.length > 1 ? Math.max(0, last - values[values.length - 2]) : 0;
        return last + step * (level - values.length + 1);
    }
}
