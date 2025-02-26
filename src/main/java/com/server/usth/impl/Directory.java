package com.server.usth.impl;

import com.server.usth.services.DaemonService;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public interface Directory extends Remote {
    void registerDaemon(String daemonId, DaemonService daemon) throws RemoteException;
    void registerFile(String filename, String daemonId) throws RemoteException;
    List<DaemonService> getDaemonsForFile(String filename) throws RemoteException;
    Set<String> getAvailableFiles() throws RemoteException;
}
