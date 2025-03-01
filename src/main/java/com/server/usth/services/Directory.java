package com.server.usth.services;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import java.util.Set;

public interface Directory extends Remote {
    // Add this method to update daemon speeds
    void reportDaemonSpeed(String daemonId, double speedKBps) throws RemoteException;

    void incrementDaemonLoad(String daemonId) throws RemoteException;

    void decrementDaemonLoad(String daemonId) throws RemoteException;

    int getDaemonLoad(String daemonId) throws RemoteException;

    void registerDaemon(String daemonId, DaemonService daemon) throws RemoteException;
    void registerFile(String filename, String daemonId) throws RemoteException;
    List<DaemonService> getDaemonsForFile(String filename) throws RemoteException;
    Set<String> getAvailableFiles() throws RemoteException;
    void heartbeat(String daemonId) throws RemoteException;
}