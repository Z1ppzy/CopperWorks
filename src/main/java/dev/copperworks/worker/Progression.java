package dev.copperworks.worker;

import dev.copperworks.CopperWorksPlugin;
import dev.copperworks.config.Messages;
import dev.copperworks.config.Settings;
import dev.copperworks.util.Format;
import dev.copperworks.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Опыт и уровни. Профессии сообщают сюда о сделанной работе, а Progression начисляет опыт
 * (с лимитом в час), повышает уровень, выдаёт черты на уровнях из experience.trait-levels
 * и уведомляет владельца. Всё хранится в PDC голема через {@link Worker}. Только главный поток.
 */
public final class Progression {
    static final long HOUR_MILLIS = 3_600_000L;

    private final CopperWorksPlugin plugin;

    /** Итог начисления: сколько опыта реально засчитано и как изменился уровень. */
    public record Grant(double amount, int from, int to, boolean capped) {
        static Grant none(int level, boolean capped) {
            return new Grant(0, level, level, capped);
        }
    }

    public Progression(CopperWorksPlugin plugin) {
        this.plugin = plugin;
    }

    /** Сортировщик перенёс предметы. */
    public void sorted(Worker worker, int items) {
        if (items <= 0) {
            return;
        }
        grant(worker, items * plugin.settings().xpSorterPerItem, () -> "sorter items=" + items, false);
    }

    /** Курьер доставил предметы: опыт за каждый предмет плюс бонус за рейс, зависящий от дистанции. */
    public void delivered(Worker worker, int items, double distance) {
        if (items <= 0) {
            return;
        }
        Settings s = plugin.settings();
        double amount = items * s.xpCourierPerItem + s.xpCourierTripBase + Math.max(0, distance) * s.xpCourierTripPerBlock;
        grant(worker, amount, () -> "courier items=" + items + " distance=" + Math.round(distance), false);
    }

    /** Фермер собрал растения. */
    public void harvested(Worker worker, int crops) {
        if (crops <= 0) {
            return;
        }
        grant(worker, crops * plugin.settings().xpFarmerPerCrop, () -> "farmer crops=" + crops, false);
    }

    /** Выдача опыта командой администратора: без лимита в час, но с обычными уровнями и чертами. */
    public Grant adminGrant(Worker worker, double amount, String admin) {
        return grant(worker, amount, () -> "команда /cw xp от " + admin, true);
    }

    /** Уровень с учётом max-level: если в конфиге его уменьшили, выше максимума не показываем. */
    public int level(Worker worker) {
        return Math.min(worker.xpLevel(), plugin.settings().maxLevel);
    }

    /** Сколько миллисекунд осталось до нового часа, если лимит опыта уже набран; иначе 0. */
    public long cappedFor(Worker worker) {
        Settings s = plugin.settings();
        if (s.xpMaxPerHour <= 0 || worker.xpWindowAmount() < s.xpMaxPerHour) {
            return 0;
        }
        long left = worker.xpWindowStart() + HOUR_MILLIS - System.currentTimeMillis();
        return left > 0 && left <= HOUR_MILLIS ? left : 0;
    }

    private Grant grant(Worker worker, double requested, Supplier<String> source, boolean bypassCap) {
        Settings s = plugin.settings();
        CopperGolem golem = worker.golem();
        int from = worker.xpLevel();
        if (!s.xpEnabled || !(requested > 0) || !Double.isFinite(requested)) {
            return Grant.none(from, false);
        }
        if (from >= s.maxLevel) {
            Log.debug("xp", () -> "golem=" + Log.id(golem) + " уже на максимальном уровне " + from
                + ", опыт за " + source.get() + " не начисляю");
            return Grant.none(from, false);
        }
        double amount = requested;
        boolean capped = false;
        if (!bypassCap && s.xpMaxPerHour > 0) {
            long now = System.currentTimeMillis();
            long start = worker.xpWindowStart();
            double used = worker.xpWindowAmount();
            if (start <= 0 || now < start || now - start >= HOUR_MILLIS) {
                start = now;
                used = 0;
            }
            double left = s.xpMaxPerHour - used;
            if (left <= 0) {
                Log.debug("xp", () -> "golem=" + Log.id(golem) + " лимит " + s.xpMaxPerHour
                    + " опыта в час набран, опыт за " + source.get() + " не начисляю");
                return Grant.none(from, true);
            }
            if (amount >= left) {
                amount = left;
                capped = true;
                long resetIn = start + HOUR_MILLIS - now;
                Log.info("xp", "golem=" + Log.id(golem) + " owner=" + Log.id(worker.owner()) + " набрал лимит "
                    + s.xpMaxPerHour + " опыта в час, следующий опыт через " + Format.duration(resetIn));
            }
            worker.xpWindow(start, used + amount);
        }

        double xp = worker.xp() + amount;
        int level = from;
        while (level < s.maxLevel && xp >= s.xpToNext(level)) {
            xp -= s.xpToNext(level);
            level++;
        }
        if (level >= s.maxLevel) {
            xp = 0;
        }
        worker.xp(xp);
        if (level != from) {
            worker.xpLevel(level);
        }
        int to = level;
        double now = xp;
        double granted = amount;
        Log.debug("xp", () -> "golem=" + Log.id(golem) + " +" + Format.decimal(granted)
            + (granted < requested ? " (из " + Format.decimal(requested) + ", лимит в час)" : "")
            + " опыта за " + source.get() + " → уровень " + to + ", опыт " + Format.decimal(now) + "/"
            + (to >= s.maxLevel ? "max" : s.xpToNext(to)));
        if (to > from) {
            levelUp(worker, from, to);
        }
        return new Grant(granted, from, to, capped);
    }

    private void levelUp(Worker worker, int from, int to) {
        Settings s = plugin.settings();
        CopperGolem golem = worker.golem();
        Job job = worker.job();
        List<Trait> gained = grantOwedTraits(worker, to);
        Log.info("xp", "golem=" + Log.id(golem) + " owner=" + Log.id(worker.owner()) + " job=" + (job == null ? "?" : job.id())
            + " повысил уровень " + from + " → " + to + (to >= s.maxLevel ? " (максимум)" : "")
            + (gained.isEmpty() ? "" : ", новые черты: " + ids(gained) + ", все черты: " + ids(worker.traits())));
        showEffect(worker, !gained.isEmpty());

        Player player = onlineOwner(worker);
        if (player == null || job == null) {
            return;
        }
        plugin.messages().send(player, to >= s.maxLevel ? "progress.level-max" : "progress.level-up",
            Placeholder.component("job", plugin.service().jobName(job)),
            Placeholder.unparsed("level", String.valueOf(to)));
        announceTraits(player, gained);
    }

    /**
     * Выдаёт черты, которые голему положены по уровню, но которых у него нет: например, после
     * изменения experience.trait-levels или когда пул был пуст. Вызывается при открытии меню.
     */
    public void catchUpTraits(Worker worker) {
        int level = level(worker);
        if (worker.traits().size() >= Traits.owed(plugin.settings(), level)) {
            return;
        }
        List<Trait> gained = grantOwedTraits(worker, level);
        if (gained.isEmpty()) {
            return;
        }
        Log.info("traits", "golem=" + Log.id(worker.golem()) + " owner=" + Log.id(worker.owner())
            + " получил положенные по уровню " + level + " черты: " + ids(gained) + ", все черты: " + ids(worker.traits()));
        showEffect(worker, true);
        Player player = onlineOwner(worker);
        if (player != null) {
            announceTraits(player, gained);
        }
    }

    /** Добирает черты до положенного на этом уровне количества. @return новые черты */
    private List<Trait> grantOwedTraits(Worker worker, int level) {
        Settings s = plugin.settings();
        CopperGolem golem = worker.golem();
        List<Trait> traits = new ArrayList<>(worker.traits());
        List<Trait> gained = new ArrayList<>();
        int owed = Traits.owed(s, level);
        while (traits.size() < owed) {
            Trait trait = Traits.roll(s, traits, null);
            if (trait == null) {
                Log.info("traits", "golem=" + Log.id(golem) + " положено черт: " + owed + ", есть: " + ids(traits)
                    + ", но в пуле не осталось подходящих (выключены, вес 0 или несовместимы) — пропускаю");
                break;
            }
            traits.add(trait);
            gained.add(trait);
        }
        if (!gained.isEmpty()) {
            worker.traits(traits);
            // Радиус и частота пересканирования могли измениться.
            plugin.registry().state(golem).invalidateCaches();
        }
        return gained;
    }

    private void showEffect(Worker worker, boolean newTrait) {
        CopperGolem golem = worker.golem();
        if (golem.isInvisible()) {
            // Курьер в рейсе невидим: эффект покажем, когда он вернётся к сундуку.
            RuntimeState state = plugin.registry().state(golem);
            state.levelUpFxPending = true;
            state.traitFxPending |= newTrait;
        } else {
            plugin.visuals().levelUp(golem, newTrait);
        }
    }

    private void announceTraits(Player player, List<Trait> gained) {
        Messages m = plugin.messages();
        Settings s = plugin.settings();
        for (Trait trait : gained) {
            m.send(player, "progress.trait-gained",
                Placeholder.component("trait", Traits.name(m, trait)),
                Placeholder.component("desc", Traits.description(m, s, trait)));
        }
    }

    private @Nullable Player onlineOwner(Worker worker) {
        UUID owner = worker.owner();
        Player player = owner == null ? null : Bukkit.getPlayer(owner);
        if (player == null) {
            Log.debug("xp", () -> "golem=" + Log.id(worker.golem()) + " владелец " + Log.id(owner)
                + " не в сети, сообщение не отправлено");
        }
        return player;
    }

    /** Отложенный эффект повышения уровня: вызывается главным циклом, когда голем снова видим. */
    public void flushEffects(CopperGolem golem, RuntimeState state) {
        if (!state.levelUpFxPending || golem.isInvisible()) {
            return;
        }
        boolean trait = state.traitFxPending;
        state.levelUpFxPending = false;
        state.traitFxPending = false;
        Log.debug("xp", () -> "golem=" + Log.id(golem) + " вернулся, показываю отложенный эффект уровня (черта: " + trait + ")");
        plugin.visuals().levelUp(golem, trait);
    }

    static String ids(List<Trait> traits) {
        return traits.stream().map(Trait::id).toList().toString();
    }
}
