package dev.copperworks.worker;

import dev.copperworks.job.SortTarget;
import java.util.List;

/** Состояние рабочего, которое живёт только в памяти (кэши, таймеры). */
public final class RuntimeState {
    public long nextActionTick;
    public boolean busy;
    public Status status = Status.STARTING;

    public List<SortTarget> sortTargets = List.of();
    public boolean sortDirty = true;
    public long sortScannedTick;
    public int sortRadius = -1;
    public BlockKey sortPost;

    public List<BlockKey> crops = List.of();
    public boolean cropsDirty = true;
    public long cropsScannedTick;
    public int cropsRadius = -1;
    public BlockKey cropsCenter;

    /** Голем повысил уровень, пока был невидим (курьер в рейсе): покажем эффект, когда вернётся. */
    public boolean levelUpFxPending;
    public boolean traitFxPending;

    public void invalidateCaches() {
        sortDirty = true;
        cropsDirty = true;
    }

    public void reset() {
        invalidateCaches();
        nextActionTick = 0;
        status = Status.STARTING;
        sortTargets = List.of();
        sortRadius = -1;
        sortPost = null;
        crops = List.of();
        cropsRadius = -1;
        cropsCenter = null;
        levelUpFxPending = false;
        traitFxPending = false;
    }
}
