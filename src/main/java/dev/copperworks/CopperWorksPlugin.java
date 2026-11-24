package dev.copperworks;

import dev.copperworks.command.CopperWorksCommand;
import dev.copperworks.config.Messages;
import dev.copperworks.config.Settings;
import dev.copperworks.job.CourierJob;
import dev.copperworks.job.FarmerJob;
import dev.copperworks.job.JobHandler;
import dev.copperworks.job.SorterJob;
import dev.copperworks.listener.GolemListener;
import dev.copperworks.ui.Dialogs;
import dev.copperworks.ui.LinkSessions;
import dev.copperworks.util.Visuals;
import dev.copperworks.worker.Job;
import dev.copperworks.worker.OwnerIndex;
import dev.copperworks.worker.WorkerRegistry;
import dev.copperworks.worker.WorkerService;
import dev.copperworks.worker.WorkerTicker;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.EnumMap;
import java.util.Map;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class CopperWorksPlugin extends JavaPlugin {
    private final Messages messages = new Messages();
    private final Map<Job, JobHandler> handlers = new EnumMap<>(Job.class);
    private Settings settings;
    private OwnerIndex ownerIndex;
    private WorkerRegistry registry;
    private Visuals visuals;
    private WorkerService service;
    private Dialogs dialogs;
    private LinkSessions links;
    private BukkitTask tickTask;
    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        Keys.init(this);
        saveDefaultConfig();
        loadConfiguration();

        ownerIndex = new OwnerIndex(this);
        ownerIndex.load();
        registry = new WorkerRegistry();
        visuals = new Visuals(this, () -> settings.visualEffects);
        handlers.put(Job.SORTER, new SorterJob(this::settings, visuals));
        handlers.put(Job.COURIER, new CourierJob(this, this::settings, messages, visuals));
        handlers.put(Job.FARMER, new FarmerJob(this::settings, visuals));
        service = new WorkerService(this);
        dialogs = new Dialogs(this);
        links = new LinkSessions(this);

        getServer().getPluginManager().registerEvents(new GolemListener(this), this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
            event -> CopperWorksCommand.register(this, event.registrar()));

        registry.scanLoadedWorlds();
        startTasks();
        getLogger().info("Рабочих големов в загруженных чанках: " + registry.entries().size());
    }

    @Override
    public void onDisable() {
        stopTasks();
        if (visuals != null) {
            visuals.cleanup();
        }
        if (ownerIndex != null) {
            ownerIndex.saveNow();
        }
    }

    public void reload() {
        loadConfiguration();
        registry.invalidateAll();
        stopTasks();
        startTasks();
    }

    private void loadConfiguration() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        messages.load(this);
    }

    private void startTasks() {
        tickTask = getServer().getScheduler().runTaskTimer(this, new WorkerTicker(this), 20L, settings.tickInterval);
        saveTask = getServer().getScheduler().runTaskTimer(this, ownerIndex::saveIfDirty, 1200L, 1200L);
    }

    private void stopTasks() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (saveTask != null) {
            saveTask.cancel();
        }
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public OwnerIndex ownerIndex() {
        return ownerIndex;
    }

    public WorkerRegistry registry() {
        return registry;
    }

    public Visuals visuals() {
        return visuals;
    }

    public WorkerService service() {
        return service;
    }

    public Dialogs dialogs() {
        return dialogs;
    }

    public LinkSessions links() {
        return links;
    }

    public JobHandler handler(Job job) {
        return handlers.get(job);
    }
}
