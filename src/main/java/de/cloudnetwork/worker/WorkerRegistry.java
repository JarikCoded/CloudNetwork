package de.cloudnetwork.worker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class WorkerRegistry {
    private final ConcurrentHashMap<String, WorkerInfo> workers = new ConcurrentHashMap<>();

    public void register(WorkerInfo worker) {
        if (worker != null && worker.getId() != null) {
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
        return new ArrayList<>(workers.values());
    }

    public void updateMetrics(String id, double cpu, double ram, int players) {
        WorkerInfo worker = workers.get(id);
        if (worker == null) {
            return;
        }
        worker.setCpuPercent(cpu);
        worker.setRamPercent(ram);
        worker.setPlayerCount(players);
        worker.setLastHeartbeatMs(System.currentTimeMillis());
    }

    public void markOffline(String id) {
        WorkerInfo worker = workers.get(id);
        if (worker == null) {
            return;
        }
        worker.setStatus(WorkerInfo.WorkerStatus.OFFLINE);
    }

    public void markOnline(String id) {
        WorkerInfo worker = workers.get(id);
        if (worker == null) {
            return;
        }
        worker.setStatus(WorkerInfo.WorkerStatus.ONLINE);
        worker.setLastHeartbeatMs(System.currentTimeMillis());
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
        List<WorkerInfo> onlineWorkers = new ArrayList<>();
        for (WorkerInfo worker : workers.values()) {
            if (worker.getStatus() == WorkerInfo.WorkerStatus.ONLINE) {
                onlineWorkers.add(worker);
            }
        }
        return onlineWorkers;
    }
}
