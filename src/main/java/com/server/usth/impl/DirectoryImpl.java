// File: src/main/java/com/server/usth/impl/DirectoryImpl.java
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

@Service
public class DirectoryImpl extends UnicastRemoteObject implements Directory {
    private final Map<String, DaemonService> daemons = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> fileRegistry = new ConcurrentHashMap<>();

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
        daemons.put(daemonId, daemon);
        System.out.println("Daemon registered: " + daemonId);
    }

    @Override
    public void registerFile(String filename, String daemonId) throws RemoteException {
        fileRegistry.computeIfAbsent(filename, k -> ConcurrentHashMap.newKeySet()).add(daemonId);
        System.out.println("File registered: " + filename + " by daemon: " + daemonId);

        // Assign the file to all available daemons if it's a new file
        if (fileRegistry.get(filename).size() == 1) {
            System.out.println("Assigning " + filename + " to all active daemons...");
            for (String activeDaemon : daemons.keySet()) {
                fileRegistry.get(filename).add(activeDaemon);
                System.out.println("File " + filename + " assigned to daemon: " + activeDaemon);
            }
        }
    }


    @Override
    public List<DaemonService> getDaemonsForFile(String filename) throws RemoteException {
        Set<String> daemonIds = fileRegistry.getOrDefault(filename, Collections.emptySet());
        List<DaemonService> availableDaemons = new ArrayList<>();
        for (String id : daemonIds) {
            DaemonService daemon = daemons.get(id);
            if (daemon != null) {
                availableDaemons.add(daemon);
            }
        }
        return availableDaemons;
    }

    // Add this method to get available files
    public Set<String> getAvailableFiles() {
        return fileRegistry.keySet();
    }
}
