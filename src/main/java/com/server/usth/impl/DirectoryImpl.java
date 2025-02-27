package com.server.usth.impl;

import com.server.usth.services.DaemonService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.rmi.RemoteException;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DirectoryImpl extends UnicastRemoteObject implements Directory {
    private static final long serialVersionUID = 1L;

    public void registerClient(String clientId, DaemonService client) throws RemoteException {
        // Store the client separately and start heartbeat tracking
        clients.put(clientId, client);
        lastHeartbeats.put(clientId, System.currentTimeMillis());
        System.out.println("Client registered: " + clientId);
    }

    private final Map<String, DaemonService> daemons = new ConcurrentHashMap<>();
    // Add a separate map for clients
    private final Map<String, DaemonService> clients = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> fileRegistry = new ConcurrentHashMap<>();
    private final Map<String, Long> lastHeartbeats = new ConcurrentHashMap<>();

    private final AtomicInteger daemonCounter = new AtomicInteger(1);
    private final AtomicInteger clientCounter = new AtomicInteger(1);

    @Override
    public synchronized String getUniqueDaemonId() throws RemoteException {
        return "daemon" + daemonCounter.getAndIncrement();
    }

    public synchronized String getUniqueClientId() throws RemoteException {
        return "client" + clientCounter.getAndIncrement();
    }

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
        } catch (Exception e) {
            System.err.println("Error binding Directory Service: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void registerDaemon(String daemonId, DaemonService daemon) throws RemoteException {
        // Check if the ID starts with "daemon" - only these are actual storage daemons
        if (daemonId.startsWith("daemon") && Integer.parseInt(daemonId.substring(6)) <= 3) {
            daemons.put(daemonId, daemon);
            System.out.println("Daemon registered: " + daemonId);
        } else {
            // Otherwise, register as a client
            clients.put(daemonId, daemon);
            System.out.println("Client registered: " + daemonId);
        }

        // Register for heartbeats regardless of type
        lastHeartbeats.put(daemonId, System.currentTimeMillis());
    }

    @Override
    public void registerFile(String filename, String daemonId) throws RemoteException {
        fileRegistry.computeIfAbsent(filename, k -> ConcurrentHashMap.newKeySet()).add(daemonId);
        System.out.println("File registered: " + filename + " by daemon: " + daemonId);

        if (fileRegistry.get(filename).size() == 1) {
            System.out.println("Assigning " + filename + " to all active daemons...");
            // Only assign to actual daemons (daemon1, daemon2, daemon3), not to clients
            for (String activeDaemon : daemons.keySet()) {
                // Additional check to ensure we only include daemon1, daemon2, daemon3
                if (activeDaemon.startsWith("daemon") && Integer.parseInt(activeDaemon.substring(6)) <= 3) {
                    fileRegistry.get(filename).add(activeDaemon);
                    System.out.println("File " + filename + " assigned to daemon: " + activeDaemon);
                }
            }
        }
    }

    @Override
    public List<DaemonService> getDaemonsForFile(String filename) throws RemoteException {
        Set<String> daemonIds = fileRegistry.getOrDefault(filename, Collections.emptySet());
        List<DaemonService> availableDaemons = new ArrayList<>();
        List<String> toRemove = new ArrayList<>();

        for (String id : daemonIds) {
            DaemonService daemon = daemons.get(id);
            if (daemon != null) {
                try {
                    daemon.getDaemonId();
                    availableDaemons.add(daemon);
                } catch (RemoteException e) {
                    System.out.println("Removing offline daemon: " + id);
                    toRemove.add(id);
                }
            }
        }

        for (String id : toRemove) {
            daemons.remove(id);
            fileRegistry.get(filename).remove(id);
        }

        System.out.println("Returning daemons for file " + filename + ": " + availableDaemons.size());
        return availableDaemons;
    }

    @Override
    public Set<String> getAvailableFiles() {
        return fileRegistry.keySet();
    }

    @Override
    public void heartbeat(String daemonId) throws RemoteException {
        lastHeartbeats.put(daemonId, System.currentTimeMillis());
        System.out.println("Heartbeat received from: " + daemonId);
    }
}