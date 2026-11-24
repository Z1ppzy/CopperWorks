package dev.copperworks.worker;

import dev.copperworks.CopperWorksPlugin;
import dev.copperworks.config.Settings;
import dev.copperworks.job.JobHandler;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.CopperGolem;

/** Главный цикл: раз в tick-interval тиков даёт поработать каждому загруженному голему, чей таймер истёк. */
public final class WorkerTicker implements Runnable {
    private final CopperWorksPlugin plugin;

    public WorkerTicker(CopperWorksPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        long tick = Bukkit.getCurrentTick();
        Settings settings = plugin.settings();
        WorkerRegistry registry = plugin.registry();
        for (WorkerRegistry.Entry entry : registry.snapshot()) {
            CopperGolem golem = entry.golem();
            RuntimeState state = entry.state();
            if (!golem.isValid()) {
                registry.untrack(golem);
                continue;
            }
            if (state.busy || tick < state.nextActionTick) {
                continue;
            }
            Worker worker = new Worker(golem);
            Job job = worker.job();
            if (job == null) {
                registry.untrack(golem);
                continue;
            }
            if (!plugin.service().settle(golem)) {
                state.nextActionTick = tick + 5;
                continue;
            }
            // Отключённая профессия замирает, но курьер в пути всё равно возвращается домой.
            if (!settings.enabled(job) && !(job == Job.COURIER && worker.phase() != Phase.IDLE)) {
                state.status = Status.DISABLED;
                state.nextActionTick = tick + 100;
                continue;
            }
            JobHandler handler = plugin.handler(job);
            int delay;
            try {
                delay = handler.work(worker, state, tick);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Ошибка в работе голема " + golem.getUniqueId(), e);
                delay = 200;
            }
            state.nextActionTick = tick + Math.max(1, delay);
        }
    }
}
