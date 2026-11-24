package dev.copperworks.worker;

import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Черта характера голема. Голем получает её на уровнях из experience.trait-levels.
 * Черта хранится при смене профессии, но действует только в своих профессиях.
 * Названия и описания — в messages.yml (trait.&lt;id&gt;), числа и веса — в config.yml (traits.&lt;id&gt;).
 * Все эффекты считает {@link Traits}.
 */
public enum Trait {
    /** «Быстроногий»: рейс курьера короче. */
    SWIFT("swift", EnumSet.of(Job.COURIER)),
    /** «Грузчик»: больше стаков за рейс и больше предметов за перенос. */
    PORTER("porter", EnumSet.of(Job.COURIER, Job.SORTER)),
    /** «Аккуратный»: сортировщик чаще пересканирует и сначала раскладывает по фильтрам. */
    TIDY("tidy", EnumSet.of(Job.SORTER)),
    /** «Жадный»: фермер иногда получает лишний предмет. */
    GREEDY("greedy", EnumSet.of(Job.FARMER)),
    /** «Неутомимый»: короче пауза между действиями. */
    TIRELESS("tireless", EnumSet.allOf(Job.class)),
    /** «Нержавейка»: окисление замедляет меньше. */
    STAINLESS("stainless", EnumSet.allOf(Job.class)),
    /** «Дальнозоркий»: больше радиус у сортировщика и фермера. */
    FARSIGHTED("farsighted", EnumSet.of(Job.SORTER, Job.FARMER)),
    /** «Ленивый, но надёжный»: медленнее, но вместимость на уровень выше. */
    LAZY("lazy", EnumSet.allOf(Job.class));

    private final String id;
    private final Set<Job> jobs;

    Trait(String id, Set<Job> jobs) {
        this.id = id;
        this.jobs = jobs;
    }

    public String id() {
        return id;
    }

    /** Действует ли черта в этой профессии. */
    public boolean appliesTo(@Nullable Job job) {
        return job != null && jobs.contains(job);
    }

    /** Взаимоисключающие пары: такие черты не выпадают одному голему. */
    public boolean conflictsWith(Trait other) {
        return excludes(this, other) || excludes(other, this);
    }

    private static boolean excludes(Trait a, Trait b) {
        return a == LAZY && (b == SWIFT || b == TIRELESS);
    }

    public static @Nullable Trait byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (Trait trait : values()) {
            if (trait.id.equals(id)) {
                return trait;
            }
        }
        return null;
    }
}
