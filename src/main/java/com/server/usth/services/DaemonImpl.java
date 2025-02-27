package com.server.usth.services;

import com.server.usth.impl.Directory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.Set;

// File: DaemonImpl.java
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

    public void start() {
        try {
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            Directory directory = (Directory) registry.lookup("Directory");

            // ✅ Register daemon first
            directory.registerDaemon(daemonId, this);
            System.out.println("Daemon " + daemonId + " is running...");

            // ✅ Ensure storage directory exists
            File storage = new File(storageDirectory);
            if (!storage.exists()) {
                storage.mkdirs();
            }

            // ✅ Scan for existing files and register them
            File[] files = storage.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        System.out.println("Registering existing file: " + file.getName());
                        directory.registerFile(file.getName(), daemonId);
                    }
                }
            }

            // ✅ Step 1: Get the list of all available files from Directory
            Set<String> availableFiles = directory.getAvailableFiles();
            for (String filename : availableFiles) {
                File localFile = new File(storageDirectory, filename);

                if (!localFile.exists()) {
                    System.out.println("File " + filename + " is missing. Requesting from other daemons...");
                    List<DaemonService> sourceDaemons = directory.getDaemonsForFile(filename);

                    if (!sourceDaemons.isEmpty()) {
                        requestFileFromDaemons(filename, sourceDaemons);
                    }
                }
            }

            System.out.println("Daemon " + daemonId + " file recovery completed.");
        } catch (Exception e) {
            System.err.println("Daemon exception: " + e.toString());
            e.printStackTrace();
        }
    }

    private void requestFileFromDaemons(String filename, List<DaemonService> sourceDaemons) {
        for (DaemonService sourceDaemon : sourceDaemons) {
            try {
                long fileSize = sourceDaemon.getFileSize(filename);
                byte[] data = sourceDaemon.downloadChunk(filename, 0, (int) fileSize);

                File file = new File(storageDirectory, filename);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(data);
                }

                System.out.println("Recovered file: " + filename);
                return; // Exit after successful recovery
            } catch (Exception e) {
                System.err.println("Failed to recover " + filename + " from a daemon. Trying another...");
            }
        }
        System.err.println("Failed to recover " + filename + " from any daemon.");
    }


    @Override
    public void receiveFile(String filename, byte[] data) throws RemoteException {
        try {
            File file = new File(storageDirectory, filename);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
            }
            System.out.println("Received and stored file: " + filename);
        } catch (IOException e) {
            throw new RemoteException("Error saving file", e);
        }
    }

    @Override
    public String getDaemonId() throws RemoteException {
        return daemonId;
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