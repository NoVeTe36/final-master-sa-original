package com.server.usth.impl;

import com.server.usth.services.DaemonService;
import com.server.usth.services.Directory;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.rmi.RemoteException;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class DirectoryImpl extends UnicastRemoteObject implements Directory {
    private static final long serialVersionUID = 1L;
    private static final long HEARTBEAT_TIMEOUT = 30000; // 30 seconds timeout

    private final Map<String, DaemonService> daemons = new ConcurrentHashMap<>();
    private final Map<String, DaemonService> clients = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> fileRegistry = new ConcurrentHashMap<>();
    private final Map<String, Long> lastHeartbeats = new ConcurrentHashMap<>();

    private final Map<String, AtomicInteger> daemonLoads = new ConcurrentHashMap<>();

    private ScheduledExecutorService heartbeatChecker;

    @Autowired
    private Registry rmiRegistry;

    protected DirectoryImpl() throws RemoteException {
        super();
    }

    @PostConstruct
    public void init() {
        try {
            rmiRegistry.rebind("Directory", this);
            System.out.println("Directory Service bound to RMI registry");

            // Start the heartbeat checker thread
            heartbeatChecker = Executors.newSingleThreadScheduledExecutor();
            heartbeatChecker.scheduleAtFixedRate(this::checkHeartbeats, 10, 10, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("Error binding Directory Service: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void checkHeartbeats() {
        long currentTime = System.currentTimeMillis();
        List<String> inactiveNodes = new ArrayList<>();

        for (Map.Entry<String, Long> entry : lastHeartbeats.entrySet()) {
            if (currentTime - entry.getValue() > HEARTBEAT_TIMEOUT) {
                inactiveNodes.add(entry.getKey());
            }
        }

        for (String nodeId : inactiveNodes) {
            if (nodeId.startsWith("daemon")) {
                daemons.remove(nodeId);
                System.out.println("Daemon removed due to inactivity: " + nodeId);

                for (Set<String> daemonSet : fileRegistry.values()) {
                    daemonSet.remove(nodeId);
                }
            } else {
                clients.remove(nodeId);
                System.out.println("Client removed due to inactivity: " + nodeId);
            }

            lastHeartbeats.remove(nodeId);
        }
    }

    @Override
    public void incrementDaemonLoad(String daemonId) throws RemoteException {
        daemonLoads.computeIfAbsent(daemonId, k -> new AtomicInteger(0)).incrementAndGet();
        System.out.println("Daemon " + daemonId + " load increased to: " + daemonLoads.get(daemonId).get());
    }

    @Override
    public void decrementDaemonLoad(String daemonId) throws RemoteException {
        AtomicInteger counter = daemonLoads.get(daemonId);
        if (counter != null && counter.get() > 0) {
            counter.decrementAndGet();
            System.out.println("Daemon " + daemonId + " load decreased to: " + counter.get());
        }
    }

    @Override
    public int getDaemonLoad(String daemonId) throws RemoteException {
        AtomicInteger counter = daemonLoads.get(daemonId);
        return counter != null ? counter.get() : 0;
    }

    @Override
    public void registerDaemon(String daemonId, DaemonService daemon) throws RemoteException {
        // Register as daemon if the ID starts with "daemon"
        if (daemonId.startsWith("daemon")) {
            daemons.put(daemonId, daemon);
            System.out.println("Daemon registered: " + daemonId);

            // Automatically assign all available files to the new daemon
            for (String filename : fileRegistry.keySet()) {
                fileRegistry.get(filename).add(daemonId);
                System.out.println("File " + filename + " assigned to daemon: " + daemonId);
            }
        } else {
            // Otherwise, register as a client
            clients.put(daemonId, daemon);
            System.out.println("Client registered: " + daemonId);
        }

        lastHeartbeats.put(daemonId, System.currentTimeMillis());
    }

    @Override
    public void registerFile(String filename, String daemonId) throws RemoteException {
        fileRegistry.computeIfAbsent(filename, k -> ConcurrentHashMap.newKeySet()).add(daemonId);
        System.out.println("File registered: " + filename + " by daemon: " + daemonId);
    }

    @Override
    public List<DaemonService> getDaemonsForFile(String filename) throws RemoteException {
        Set<String> daemonIds = fileRegistry.getOrDefault(filename, Collections.emptySet());
        List<DaemonInfo> daemonInfos = new ArrayList<>();
        List<String> toRemove = new ArrayList<>();

        // First check current heartbeats to avoid trying dead daemons
        long currentTime = System.currentTimeMillis();
        for (String id : daemonIds) {
            // Skip daemons that haven't sent a heartbeat recently
            if (currentTime - lastHeartbeats.getOrDefault(id, 0L) > HEARTBEAT_TIMEOUT) {
                System.out.println("Skipping inactive daemon: " + id);
                toRemove.add(id);
                continue;
            }

            DaemonService daemon = daemons.get(id);
            if (daemon != null) {
                try {
                    // Verify daemon has the file by checking its size
                    long fileSize = daemon.getFileSize(filename);
                    if (fileSize > 0) {
                        // Get the current load for this daemon
                        int load = getDaemonLoad(id);
                        daemonInfos.add(new DaemonInfo(daemon, load));
                    } else {
                        System.out.println("Daemon " + id + " doesn't have file: " + filename);
                        toRemove.add(id);
                    }
                } catch (RemoteException e) {
                    System.out.println("Removing offline daemon: " + id);
                    toRemove.add(id);
                }
            }
        }

        for (String id : toRemove) {
            fileRegistry.get(filename).remove(id);
        }

        // Sort daemons by load (lowest load first)
        Collections.sort(daemonInfos);

        System.out.println("Daemons for file " + filename + " sorted by load:");
        for (DaemonInfo info : daemonInfos) {
            try {
                System.out.println("  - " + info.daemon.getDaemonId() + " (load: " + info.load + ")");
            } catch (RemoteException e) {
                // Ignore
            }
        }

        // Extract the sorted daemons
        List<DaemonService> sortedDaemons = daemonInfos.stream()
                .map(info -> info.daemon)
                .collect(Collectors.toList());

        return sortedDaemons;
    }

    // Helper class to store daemon and its load for sorting
    private static class DaemonInfo implements Comparable<DaemonInfo> {
        DaemonService daemon;
        int load;

        DaemonInfo(DaemonService daemon, int load) {
            this.daemon = daemon;
            this.load = load;
        }

        @Override
        public int compareTo(DaemonInfo other) {
            return Integer.compare(this.load, other.load);
        }
    }

    @Override
    public Set<String> getAvailableFiles() {
        return fileRegistry.keySet();
    }

    @Override
    public void heartbeat(String nodeId) throws RemoteException {
        lastHeartbeats.put(nodeId, System.currentTimeMillis());
        if (nodeId.startsWith("daemon") && !daemons.containsKey(nodeId)) {
            System.out.println("Detected new daemon from heartbeat: " + nodeId);
            try {
                DaemonService daemon = (DaemonService) rmiRegistry.lookup(nodeId);
                if (daemon != null) {
                    registerDaemon(nodeId, daemon);
                }
            } catch (Exception e) {
                System.out.println("Could not lookup daemon from registry: " + e.getMessage());
            }
        }

        System.out.println("Heartbeat received from: " + nodeId);
    }
}