package de.cloudnetwork.worker;

import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class WorkerRegistry {
    private final ConcurrentHashMap<String, WorkerInfo> workers = new ConcurrentHashMap<>();

    public void register(WorkerInfo worker) {
        if (worker != null && worker.getId() != null && !worker.getId().isBlank()) {
            workers.put(worker.getId(), worker);
        }
    }

    public WorkerInfo get(String id) {
        return workers.get(id);
    }

    public WorkerInfo remove(String id) {
        return workers.remove(id);
    }

    public Collection<WorkerInfo> getAll() {
        return workers.values().stream()
                .sorted(Comparator.comparing(WorkerInfo::getId, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    public void updateMetrics(String id, double cpu, double ram, int players) {
        WorkerInfo worker = workers.get(id);
        if (worker == null) {
            return;
        }
        worker.applyMetrics(cpu, ram, players);
    }

    public void markOffline(String id) {
        WorkerInfo worker = workers.get(id);
        if (worker == null) {
            return;
        }
        worker.markOffline();
    }

    public void markOnline(String id) {
        WorkerInfo worker = workers.get(id);
        if (worker == null) {
            return;
        }
        worker.markOnline();
    }

    public double getAverageCpuLoad() {
        List<WorkerInfo> onlineWorkers = getOnlineWorkers();
        if (onlineWorkers.isEmpty()) {
            return 0.0D;
        }
        double total = 0.0D;
        for (WorkerInfo worker : onlineWorkers) {
            total += worker.getCpuPercent();
        }
        return total / onlineWorkers.size();
    }

    public double getAverageTotalLoad() {
        List<WorkerInfo> onlineWorkers = getOnlineWorkers();
        if (onlineWorkers.isEmpty()) {
            return 0.0D;
        }
        double total = 0.0D;
        for (WorkerInfo worker : onlineWorkers) {
            total += (worker.getCpuPercent() + worker.getRamPercent()) / 2.0D;
        }
        return total / onlineWorkers.size();
    }

    private List<WorkerInfo> getOnlineWorkers() {
        return getAll().stream()
                .filter(worker -> worker.getStatus() == WorkerInfo.WorkerStatus.ONLINE)
                .toList();
    }
}
