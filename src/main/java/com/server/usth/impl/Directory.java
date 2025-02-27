package com.server.usth.impl;

import com.server.usth.services.DaemonService;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import java.util.Set;

public interface Directory extends Remote {
    void registerDaemon(String daemonId, DaemonService daemon) throws RemoteException;
    void registerFile(String filename, String daemonId) throws RemoteException;
    void registerClient(String clientId, DaemonService client) throws RemoteException;
    List<DaemonService> getDaemonsForFile(String filename) throws RemoteException;
    Set<String> getAvailableFiles() throws RemoteException;
    void heartbeat(String daemonId) throws RemoteException;
    String getUniqueDaemonId() throws RemoteException;
    // Add a new method for getting client IDs
    String getUniqueClientId() throws RemoteException;
}