package dev.copperworks.job;

import dev.copperworks.worker.RuntimeState;
import dev.copperworks.worker.Worker;

public interface JobHandler {
    /**
     * Один шаг работы. Вызывается в главном потоке.
     *
     * @return через сколько тиков вызвать снова
     */
    int work(Worker worker, RuntimeState state, long tick);
}
