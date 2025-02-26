package com.server.usth.services;

import com.server.usth.impl.Directory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

public interface DaemonService extends Remote {
    byte[] downloadChunk(String filename, long offset, int size) throws RemoteException;
    long getFileSize(String filename) throws RemoteException;
    void receiveFile(String filename, byte[] data) throws RemoteException;
    String getDaemonId() throws RemoteException;
}

