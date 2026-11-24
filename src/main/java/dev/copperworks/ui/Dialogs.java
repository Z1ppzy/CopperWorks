package dev.copperworks.ui;

import dev.copperworks.CopperWorksPlugin;
import dev.copperworks.config.Messages;
import dev.copperworks.config.Settings;
import dev.copperworks.job.Frames;
import dev.copperworks.job.Posts;
import dev.copperworks.job.SortTarget;
import dev.copperworks.util.Format;
import dev.copperworks.util.Log;
import dev.copperworks.worker.Job;
import dev.copperworks.worker.Phase;
import dev.copperworks.worker.RuntimeState;
import dev.copperworks.worker.Status;
import dev.copperworks.worker.Trait;
import dev.copperworks.worker.Traits;
import dev.copperworks.worker.Upgrade;
import dev.copperworks.worker.Worker;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Меню голема на нативных диалогах (Minecraft 1.21.6+): без сундуков-GUI, с кнопками и подсказками.
 * Каждая кнопка — одноразовый колбэк, все действия перепроверяют голема и дистанцию.
 */
public final class Dialogs {
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
        .uses(1)
        .lifetime(Duration.ofMinutes(5))
        .build();
    private static final int BODY_WIDTH = 320;
    private static final int BUTTON_WIDTH = 150;
    private static final double MAX_DISTANCE = 12;
    private static final int XP_BAR_SEGMENTS = 10;

    private final CopperWorksPlugin plugin;

    public Dialogs(CopperWorksPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    private Settings s() {
        return plugin.settings();
    }

    public void open(Player player, CopperGolem golem) {
        Worker worker = Worker.of(golem);
        Log.debug("dialog", () -> "player=" + player.getName() + " открыл меню golem=" + Log.id(golem)
            + (worker == null ? " (свободен)" : " job=" + (worker.job() == null ? "?" : worker.job().id())
            + " owner=" + Log.id(worker.owner()) + " level=" + worker.xpLevel()));
        if (worker == null) {
            showHire(player, golem);
            return;
        }
        if (!plugin.service().canManage(player, worker)) {
            m().send(player, "not-owner", Placeholder.unparsed("owner", ownerName(worker)));
            return;
        }
        plugin.progression().catchUpTraits(worker);
        showManage(player, golem);
    }

    // ── Найм ──────────────────────────────────────────────

    private void showHire(Player player, CopperGolem golem) {
        List<ActionButton> buttons = jobButtons(golem, null, (p, g, job) -> {
            if (plugin.service().hire(p, g, job)) {
                showManage(p, g);
            }
        });
        if (buttons.isEmpty()) {
            m().send(player, "hire.job-disabled");
            return;
        }
        show(player, m().get("dialog.hire.title"),
            List.of(DialogBody.plainMessage(m().get("dialog.hire.body"), BODY_WIDTH)),
            buttons, 1, closeButton());
    }

    // ── Управление ────────────────────────────────────────

    public void showManage(Player player, CopperGolem golem) {
        Worker worker = Worker.of(golem);
        if (worker == null || worker.job() == null) {
            showHire(player, golem);
            return;
        }
        Job job = worker.job();
        RuntimeState state = plugin.registry().state(golem);

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.item(new ItemStack(job.icon()))
            .description(DialogBody.plainMessage(m().get("job." + job.id() + ".desc"), BODY_WIDTH - 40))
            .showTooltip(false)
            .build());
        body.add(DialogBody.plainMessage(m().get("dialog.manage.info",
            Placeholder.unparsed("owner", ownerName(worker)),
            Placeholder.component("status", statusLine(worker, state)),
            Placeholder.component("oxidation", oxidation(golem)),
            Placeholder.unparsed("speed", String.valueOf(Math.round(Traits.speedFactor(s(), worker) * 100))),
            Placeholder.unparsed("lvl_speed", String.valueOf(worker.level(Upgrade.SPEED))),
            Placeholder.unparsed("lvl_capacity", String.valueOf(worker.level(Upgrade.CAPACITY))),
            Placeholder.unparsed("lvl_range", String.valueOf(worker.level(Upgrade.RANGE))),
            Placeholder.unparsed("moved", String.valueOf(worker.moved()))), BODY_WIDTH));
        if (s().xpEnabled) {
            body.add(DialogBody.plainMessage(experienceLine(worker), BODY_WIDTH));
        }
        Component traits = traitsBlock(worker);
        if (traits != null) {
            body.add(DialogBody.plainMessage(traits, BODY_WIDTH));
        }
        body.add(DialogBody.plainMessage(jobInfo(worker, state), BODY_WIDTH));
        body.add(DialogBody.plainMessage(m().get("dialog.manage.tip"), BODY_WIDTH));

        List<ActionButton> buttons = new ArrayList<>();
        for (Upgrade upgrade : Upgrade.values()) {
            buttons.add(upgradeButton(worker, upgrade));
        }
        if (job == Job.COURIER) {
            buttons.add(button(m().get("dialog.manage.set-target"), m().get("dialog.manage.set-target-tooltip"), golem,
                (p, g) -> plugin.links().start(p, g)));
        }
        if (canReroll(worker)) {
            buttons.add(button(m().get("dialog.manage.reroll"),
                m().get("dialog.manage.reroll-tooltip", price(s().rerollCost)), golem, this::showRerollPick));
        }
        buttons.add(button(m().get("dialog.manage.change-job"), null, golem, this::showChangeJob));
        buttons.add(button(m().get("dialog.manage.fire"), null, golem, this::showFireConfirm));

        show(player, m().get("dialog.manage.title", Placeholder.component("job", plugin.service().jobName(job))),
            body, buttons, 2, closeButton());
    }

    private void showChangeJob(Player player, CopperGolem golem) {
        Worker worker = Worker.of(golem);
        if (worker == null) {
            return;
        }
        List<ActionButton> buttons = jobButtons(golem, worker.job(), (p, g, job) -> {
            plugin.service().changeJob(p, g, job);
            showManage(p, g);
        });
        if (buttons.isEmpty()) {
            showManage(player, golem);
            return;
        }
        show(player, m().get("dialog.change-job.title"), List.of(), buttons, 1,
            button(m().get("dialog.back"), null, golem, this::showManage));
    }

    private void showFireConfirm(Player player, CopperGolem golem) {
        ActionButton yes = button(m().get("dialog.fire.confirm"), null, golem, (p, g) -> plugin.service().fire(p, g));
        ActionButton no = button(m().get("dialog.fire.cancel"), null, golem, this::showManage);
        Dialog dialog = Dialog.create(factory -> factory.empty()
            .base(DialogBase.builder(m().get("dialog.fire.title"))
                .body(List.of(DialogBody.plainMessage(m().get("dialog.fire.body"), BODY_WIDTH)))
                .build())
            .type(DialogType.confirmation(yes, no)));
        player.showDialog(dialog);
    }

    // ── Переобучение ──────────────────────────────────────

    private boolean canReroll(Worker worker) {
        return s().traitsEnabled && s().rerollEnabled && !worker.traits().isEmpty();
    }

    /** Какую черту забыть. Если черта одна — сразу подтверждение. */
    private void showRerollPick(Player player, CopperGolem golem) {
        Worker worker = Worker.of(golem);
        if (worker == null || !canReroll(worker)) {
            showManage(player, golem);
            return;
        }
        List<Trait> traits = worker.traits();
        if (traits.size() == 1) {
            showRerollConfirm(player, golem, traits.getFirst());
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (Trait trait : traits) {
            buttons.add(button(Traits.name(m(), trait), Traits.description(m(), s(), trait), golem,
                (p, g) -> showRerollConfirm(p, g, trait)));
        }
        show(player, m().get("dialog.reroll.title"),
            List.of(DialogBody.plainMessage(m().get("dialog.reroll.body", price(s().rerollCost)), BODY_WIDTH)),
            buttons, 1, button(m().get("dialog.back"), null, golem, this::showManage));
    }

    /** Подтверждение в стиле увольнения: цена и что именно голем забудет. */
    private void showRerollConfirm(Player player, CopperGolem golem, Trait trait) {
        ActionButton yes = button(m().get("dialog.reroll-confirm.confirm"), null, golem, (p, g) -> {
            if (plugin.service().reroll(p, g, trait)) {
                showManage(p, g);
            }
        });
        ActionButton no = button(m().get("dialog.reroll-confirm.cancel"), null, golem, this::showManage);
        Component body = m().get("dialog.reroll-confirm.body",
            Placeholder.component("trait", Traits.name(m(), trait)), price(s().rerollCost));
        Dialog dialog = Dialog.create(factory -> factory.empty()
            .base(DialogBase.builder(m().get("dialog.reroll-confirm.title"))
                .body(List.of(DialogBody.plainMessage(body, BODY_WIDTH)))
                .build())
            .type(DialogType.confirmation(yes, no)));
        player.showDialog(dialog);
    }

    /** Плейсхолдеры цены: &lt;cost&gt; и &lt;item&gt;. */
    private TagResolver price(int cost) {
        return TagResolver.resolver(
            Placeholder.unparsed("cost", String.valueOf(cost)),
            Placeholder.component("item", Component.translatable(s().upgradeItem)));
    }

    // ── Части диалогов ────────────────────────────────────

    /** «Уровень 4/10 ▮▮▮▯▯▯▯▯▯▯ 340/800». */
    private Component experienceLine(Worker worker) {
        Settings s = s();
        int level = plugin.progression().level(worker);
        if (level >= s.maxLevel) {
            return m().get("dialog.manage.experience-max",
                Placeholder.unparsed("level", String.valueOf(level)),
                Placeholder.unparsed("max", String.valueOf(s.maxLevel)));
        }
        long need = s.xpToNext(level);
        long xp = Math.min((long) Math.floor(worker.xp()), need);
        int filled = (int) Math.clamp(Math.floor((double) xp / need * XP_BAR_SEGMENTS), 0, XP_BAR_SEGMENTS);
        Component bar = m().get("dialog.manage.xp-bar",
            Placeholder.unparsed("filled", m().raw("dialog.manage.xp-bar-filled").repeat(filled)),
            Placeholder.unparsed("empty", m().raw("dialog.manage.xp-bar-empty").repeat(XP_BAR_SEGMENTS - filled)));
        Component line = m().get("dialog.manage.experience",
            Placeholder.unparsed("level", String.valueOf(level)),
            Placeholder.unparsed("max", String.valueOf(s.maxLevel)),
            Placeholder.component("bar", bar),
            Placeholder.unparsed("xp", String.valueOf(xp)),
            Placeholder.unparsed("need", String.valueOf(need)));
        long capped = plugin.progression().cappedFor(worker);
        if (capped > 0) {
            line = line.appendNewline().append(m().get("dialog.manage.xp-capped",
                Placeholder.unparsed("time", Format.duration(capped))));
        }
        return line;
    }

    /** Черты с описаниями. Чужая профессия и выключенные черты — серым. null — блок не нужен. */
    private @Nullable Component traitsBlock(Worker worker) {
        Settings s = s();
        List<Trait> traits = worker.traits();
        if (!s.traitsEnabled && traits.isEmpty()) {
            return null;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(m().get("dialog.manage.traits-title"));
        if (traits.isEmpty()) {
            lines.add(m().get("dialog.manage.traits-empty"));
        }
        for (Trait trait : traits) {
            String key = !s.traitEnabled(trait) ? "dialog.manage.trait-line-disabled"
                : trait.appliesTo(worker.job()) ? "dialog.manage.trait-line"
                : "dialog.manage.trait-line-inactive";
            lines.add(m().get(key,
                Placeholder.component("trait", Traits.name(m(), trait)),
                Placeholder.component("desc", Traits.description(m(), s, trait))));
        }
        Integer next = s.xpEnabled ? Traits.nextTraitLevel(s, plugin.progression().level(worker), traits.size()) : null;
        if (next != null) {
            lines.add(m().get("dialog.manage.trait-next", Placeholder.unparsed("level", String.valueOf(next))));
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private Component statusLine(Worker worker, RuntimeState state) {
        Status status = state.status;
        if (worker.job() == Job.COURIER) {
            Phase phase = worker.phase();
            if (phase == Phase.OUTBOUND) {
                status = Status.ON_TRIP;
            } else if (phase == Phase.RETURNING) {
                status = Status.RETURNING;
            }
        }
        long left = worker.phaseUntil() - System.currentTimeMillis();
        return m().get(status.messageKey(), Placeholder.unparsed("time", Format.duration(left)));
    }

    private Component oxidation(CopperGolem golem) {
        Component state = m().get("oxidation." + golem.getWeatheringState().name().toLowerCase(Locale.ROOT));
        if (golem.getOxidizing() instanceof CopperGolem.Oxidizing.Waxed) {
            return state.append(m().get("oxidation.waxed"));
        }
        return state;
    }

    private Component jobInfo(Worker worker, RuntimeState state) {
        Settings s = s();
        return switch (worker.job()) {
            case SORTER -> {
                long filtered = state.sortTargets.stream().filter(SortTarget::hasFilters).count();
                yield m().get("dialog.manage.sorter-info",
                    Placeholder.unparsed("radius", String.valueOf(Traits.sorterRadius(s, worker))),
                    Placeholder.unparsed("targets", String.valueOf(state.sortTargets.size())),
                    Placeholder.unparsed("filtered", String.valueOf(filtered)));
            }
            case COURIER -> {
                Location target = worker.target();
                Component where = target == null
                    ? m().get("dialog.manage.courier-no-target")
                    : Component.text(target.getBlockX() + " " + target.getBlockY() + " " + target.getBlockZ()
                        + (target.getWorld().equals(worker.golem().getWorld())
                        ? " (" + Math.round(target.distance(worker.golem().getLocation())) + " бл.)" : ""));
                Block post = Posts.resolve(worker, s);
                Set<Material> filter = s.courierFrameFilter && post != null ? Frames.on(post) : Set.of();
                Component filterText = filter.isEmpty()
                    ? m().get("dialog.manage.courier-filter-all")
                    : m().get("dialog.manage.courier-filter-frames", Placeholder.unparsed("count", String.valueOf(filter.size())));
                yield m().get("dialog.manage.courier-info",
                    Placeholder.component("target", where),
                    Placeholder.unparsed("stacks", String.valueOf(Traits.courierStacks(s, worker))),
                    Placeholder.component("filter", filterText));
            }
            case FARMER -> m().get("dialog.manage.farmer-info",
                Placeholder.unparsed("radius", String.valueOf(Traits.farmerRadius(s, worker))),
                Placeholder.unparsed("crops", String.valueOf(state.crops.size())));
            case null -> Component.empty();
        };
    }

    private ActionButton upgradeButton(Worker worker, Upgrade upgrade) {
        int level = worker.level(upgrade);
        Component name = m().get("upgrade-name." + upgrade.id());
        Component current = upgradeValue(worker, upgrade, level);
        if (level >= Upgrade.MAX_LEVEL) {
            return button(m().get("dialog.manage.upgrade-button-max", Placeholder.component("upgrade", name)),
                m().get("dialog.manage.upgrade-tooltip-max", Placeholder.component("current", current)),
                worker.golem(), (p, g) -> showManage(p, g));
        }
        Component label = m().get("dialog.manage.upgrade-button",
            Placeholder.component("upgrade", name),
            Placeholder.unparsed("level", Format.roman(level)),
            Placeholder.unparsed("next", Format.roman(level + 1)));
        Component tooltip = m().get("dialog.manage.upgrade-tooltip",
            Placeholder.unparsed("cost", String.valueOf(s().upgradeCost(level))),
            Placeholder.component("item", Component.translatable(s().upgradeItem)),
            Placeholder.component("current", current),
            Placeholder.component("future", upgradeValue(worker, upgrade, level + 1)));
        return button(label, tooltip, worker.golem(), (p, g) -> {
            plugin.service().upgrade(p, g, upgrade);
            showManage(p, g);
        });
    }

    private Component upgradeValue(Worker worker, Upgrade upgrade, int level) {
        Settings s = s();
        Job job = worker.job();
        return switch (upgrade) {
            case SPEED -> value("values.speed", Format.decimal(1.0 + s.speedPerLevel * level));
            // С учётом черт («Грузчик», «Ленивый», «Дальнозоркий»), чтобы подсказка совпадала с работой.
            case CAPACITY -> switch (job) {
                case SORTER -> value("values.sorter-capacity", String.valueOf(Traits.sorterItemsPerMove(s, worker, level)));
                case COURIER -> value("values.courier-capacity", String.valueOf(Traits.courierStacks(s, worker, level)));
                case FARMER -> value("values.farmer-capacity", String.valueOf(Traits.farmerCropsPerAction(s, worker, level)));
                case null -> Component.empty();
            };
            case RANGE -> switch (job) {
                case SORTER -> value("values.radius", String.valueOf(Traits.sorterRadius(s, worker, level)));
                case FARMER -> value("values.radius", String.valueOf(Traits.farmerRadius(s, worker, level)));
                case COURIER -> s.courierMaxDistance[level] < 0
                    ? m().get("values.unlimited")
                    : value("values.distance", String.valueOf(s.courierMaxDistance[level]));
                case null -> Component.empty();
            };
        };
    }

    private Component value(String key, String value) {
        return m().get(key, Placeholder.unparsed("value", value));
    }

    @FunctionalInterface
    private interface JobAction {
        void run(Player player, CopperGolem golem, Job job);
    }

    private List<ActionButton> jobButtons(CopperGolem golem, @Nullable Job except, JobAction action) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Job job : Job.values()) {
            if (job == except || !s().enabled(job)) {
                continue;
            }
            buttons.add(button(plugin.service().jobName(job), m().get("job." + job.id() + ".desc"), golem,
                (p, g) -> action.run(p, g, job)));
        }
        return buttons;
    }

    private ActionButton closeButton() {
        return ActionButton.builder(m().get("dialog.close")).width(BUTTON_WIDTH).build();
    }

    private ActionButton button(Component label, @Nullable Component tooltip, CopperGolem golem,
                                BiConsumer<Player, CopperGolem> action) {
        UUID golemId = golem.getUniqueId();
        String plainLabel = PlainTextComponentSerializer.plainText().serialize(label);
        ActionButton.Builder builder = ActionButton.builder(label)
            .width(BUTTON_WIDTH)
            .action(DialogAction.customClick((response, audience) -> {
                if (!(audience instanceof Player player)) {
                    return;
                }
                Runnable task = () -> {
                    Log.debug("dialog", () -> "player=" + player.getName() + " нажал «" + plainLabel + "» golem="
                        + Log.id(golemId));
                    CopperGolem target = resolve(player, golemId);
                    if (target != null) {
                        action.accept(player, target);
                    }
                };
                if (Bukkit.isPrimaryThread()) {
                    task.run();
                } else {
                    Bukkit.getScheduler().runTask(plugin, task);
                }
            }, ONCE));
        if (tooltip != null) {
            builder.tooltip(tooltip);
        }
        return builder.build();
    }

    private void show(Player player, Component title, List<? extends DialogBody> body,
                      List<ActionButton> buttons, int columns, ActionButton exit) {
        Dialog dialog = Dialog.create(factory -> factory.empty()
            .base(DialogBase.builder(title).body(body).build())
            .type(DialogType.multiAction(buttons).columns(columns).exitAction(exit).build()));
        player.showDialog(dialog);
    }

    /** Голем ещё существует, игрок рядом и всё ещё вправе им управлять. */
    private @Nullable CopperGolem resolve(Player player, UUID golemId) {
        Entity entity = Bukkit.getEntity(golemId);
        if (!(entity instanceof CopperGolem golem) || !golem.isValid()) {
            Log.debug("dialog", () -> "player=" + player.getName() + " golem=" + Log.id(golemId) + " уже не существует");
            m().send(player, "golem-gone");
            return null;
        }
        if (!golem.getWorld().equals(player.getWorld())
            || golem.getLocation().distanceSquared(player.getLocation()) > MAX_DISTANCE * MAX_DISTANCE) {
            Log.debug("dialog", () -> "player=" + player.getName() + " golem=" + Log.id(golemId) + " дальше "
                + MAX_DISTANCE + " бл. или в другом мире");
            m().send(player, "too-far");
            return null;
        }
        Worker worker = Worker.of(golem);
        if (worker != null && !plugin.service().canManage(player, worker)) {
            Log.debug("dialog", () -> "player=" + player.getName() + " golem=" + Log.id(golemId) + " чужой, owner="
                + Log.id(worker.owner()));
            m().send(player, "not-owner", Placeholder.unparsed("owner", ownerName(worker)));
            return null;
        }
        return golem;
    }

    private static String ownerName(Worker worker) {
        UUID owner = worker.owner();
        if (owner == null) {
            return "?";
        }
        String name = Bukkit.getOfflinePlayer(owner).getName();
        return name != null ? name : owner.toString().substring(0, 8);
    }
}
