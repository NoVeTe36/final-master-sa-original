package com.server.usth.services;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface DaemonService extends Remote {
    byte[] downloadChunk(String filename, long offset, int size) throws RemoteException;
    long getFileSize(String filename) throws RemoteException;
    void receiveFile(String filename, byte[] data) throws RemoteException;
    String getDaemonId() throws RemoteException;
}