package com.server.usth.services;

import com.server.usth.impl.Directory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

public class DaemonImpl extends UnicastRemoteObject implements DaemonService {
    private final String daemonId;
    private final String storageDirectory;

    public DaemonImpl(String daemonId, String storageDirectory) throws RemoteException {
        super();
        this.daemonId = daemonId;
        this.storageDirectory = storageDirectory;
    }

    @Override
    public byte[] downloadChunk(String filename, long offset, int size) throws RemoteException {
        try (RandomAccessFile file = new RandomAccessFile(storageDirectory + "/" + filename, "r")) {
            byte[] chunk = new byte[size];
            file.seek(offset);
            int bytesRead = file.read(chunk);
            if (bytesRead < size) {
                byte[] actualChunk = new byte[bytesRead];
                System.arraycopy(chunk, 0, actualChunk, 0, bytesRead);
                return actualChunk;
            }
            return chunk;
        } catch (IOException e) {
            throw new RemoteException("Error reading chunk", e);
        }
    }

    @Override
    public long getFileSize(String filename) throws RemoteException {
        File file = new File(storageDirectory + "/" + filename);
        return file.length();
    }

    @Override
    public String[] listFiles() throws RemoteException {
        File directory = new File(storageDirectory);
        String[] fileList = directory.list();
        return fileList != null ? fileList : new String[0];
    }

    @Override
    public String getDaemonId() throws RemoteException {
        return daemonId;
    }

    public void start() {
        try {
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            Directory directory = (Directory) registry.lookup("Directory");
            directory.registerDaemon(daemonId, this);

            // Register available files
            File storage = new File(storageDirectory);
            if (storage.exists() && storage.isDirectory()) {
                File[] files = storage.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile()) {
                            directory.registerFile(file.getName(), daemonId);
                            System.out.println("Registered file: " + file.getName());
                        }
                    }
                }
            }

            System.out.println("Daemon " + daemonId + " is running...");
        } catch (Exception e) {
            System.err.println("Daemon exception: " + e.toString());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: DaemonImpl <daemonId> <storageDirectory>");
            return;
        }

        try {
            DaemonImpl daemon = new DaemonImpl(args[0], args[1]);
            daemon.start();
        } catch (Exception e) {
            System.err.println("Daemon startup failed: " + e.toString());
            e.printStackTrace();
        }
    }
}