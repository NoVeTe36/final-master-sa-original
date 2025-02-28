package com.server.usth.services;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.server.usth.impl.Directory;

public class DaemonImpl extends UnicastRemoteObject implements DaemonService {
    private final String daemonId;
    private final String storageDirectory;
    private Directory directory;
    private Registry registry;
    private ScheduledExecutorService heartbeatScheduler;
    private ScheduledExecutorService fileMonitorScheduler;
    private final long scanInterval = 30; // Seconds between file system scans

    public DaemonImpl(String daemonId, String storageDirectory) throws RemoteException {
        super();
        this.daemonId = daemonId;
        this.storageDirectory = storageDirectory;
    }

    public byte[] downloadChunk(String fileName, long offset, int size) throws RemoteException {
        File file = new File(storageDirectory, fileName);
        if (!file.exists() || size <= 0) {
            return new byte[0];
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            byte[] chunk = new byte[size];
            int bytesRead = raf.read(chunk);
            if (bytesRead < size && bytesRead > 0) {
                byte[] partial = new byte[bytesRead];
                System.arraycopy(chunk, 0, partial, 0, bytesRead);
                return partial;
            }
            return chunk;
        } catch (IOException e) {
            throw new RemoteException("Chunk read error", e);
        }
    }

    @Override
    public long getFileSize(String filename) throws RemoteException {
        File file = new File(storageDirectory + "/" + filename);
        return file.length();
    }

    @Override
    public void receiveFile(String filename, byte[] data) throws RemoteException {
        try {
            File file = new File(storageDirectory, filename);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
            }
            System.out.println("Received and stored file: " + filename);

            // Register the newly received file
            try {
                directory.registerFile(filename, daemonId);
            } catch (Exception e) {
                System.err.println("Failed to register received file: " + e.getMessage());
            }
        } catch (IOException e) {
            throw new RemoteException("Error saving file", e);
        }
    }

    @Override
    public String getDaemonId() throws RemoteException {
        return daemonId;
    }

    public void start() {
        try {
            registry = LocateRegistry.getRegistry("localhost", 1099);
            directory = (Directory) registry.lookup("Directory");

            // Bind this daemon in the registry for discovery
            registry.rebind(daemonId, this);

            // Register with directory service
            directory.registerDaemon(daemonId, this);
            System.out.println("Daemon " + daemonId + " is running...");

            // Create storage directory if it doesn't exist
            File storage = new File(storageDirectory);
            if (!storage.exists()) {
                storage.mkdirs();
            }

            // Register existing files
            registerExistingFiles();

            // Start heartbeat scheduler
            startHeartbeatScheduler();

            // Start file monitoring
            startFileMonitoring();

            // Synchronize missing files
            syncMissingFiles();

            System.out.println("Daemon " + daemonId + " initialization completed.");

            // Keep daemon running
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        } catch (Exception e) {
            System.err.println("Daemon exception: " + e.toString());
            e.printStackTrace();
        }
    }

    private void registerExistingFiles() {
        try {
            File[] files = new File(storageDirectory).listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        System.out.println("Registering existing file: " + file.getName());
                        directory.registerFile(file.getName(), daemonId);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error registering existing files: " + e.getMessage());
        }
    }

    private void startHeartbeatScheduler() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                directory.heartbeat(daemonId);
            } catch (Exception e) {
                System.err.println("Failed to send heartbeat: " + e.getMessage());
            }
        }, 0, 10, TimeUnit.SECONDS);
    }

    private void startFileMonitoring() {
        fileMonitorScheduler = Executors.newSingleThreadScheduledExecutor();
        fileMonitorScheduler.scheduleAtFixedRate(() -> {
            try {
                File[] files = new File(storageDirectory).listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile()) {
                            try {
                                directory.registerFile(file.getName(), daemonId);
                            } catch (Exception e) {
                                // Ignore if file is already registered
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Error in file monitoring: " + e.getMessage());
            }
        }, scanInterval, scanInterval, TimeUnit.SECONDS);
    }

    private void syncMissingFiles() {
        try {
            // Get all available files from directory service
            Set<String> availableFiles = directory.getAvailableFiles();
            System.out.println("Checking for missing files. Available files in network: " + availableFiles.size());

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
            System.out.println("File synchronization completed.");
        } catch (Exception e) {
            System.err.println("Error during file synchronization: " + e.getMessage());
        }
    }

    private void requestFileFromDaemons(String filename, List<DaemonService> sourceDaemons) {
        for (DaemonService sourceDaemon : sourceDaemons) {
            try {
                // Skip self
                if (sourceDaemon.getDaemonId().equals(daemonId)) {
                    continue;
                }

                long fileSize = sourceDaemon.getFileSize(filename);
                if (fileSize <= 0) {
                    continue; // Skip if file size is invalid
                }

                System.out.println("Requesting " + filename + " (" + fileSize + " bytes) from " +
                        sourceDaemon.getDaemonId());

                // For large files, download in chunks
                if (fileSize > 50 * 1024 * 1024) { // 50MB threshold
                    downloadLargeFile(filename, fileSize, sourceDaemon);
                } else {
                    // For smaller files, download in one go
                    byte[] data = sourceDaemon.downloadChunk(filename, 0, (int) fileSize);
                    if (data != null && data.length > 0) {
                        File file = new File(storageDirectory, filename);
                        try (FileOutputStream fos = new FileOutputStream(file)) {
                            fos.write(data);
                        }
                        System.out.println("Recovered file: " + filename);

                        // Register the file
                        directory.registerFile(filename, daemonId);
                        return; // Exit after successful recovery
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to recover " + filename + " from daemon " +
                        "trying another daemon: " + e.getMessage());
            }
        }
        System.err.println("Failed to recover " + filename + " from any daemon.");
    }

    private void downloadLargeFile(String filename, long fileSize, DaemonService sourceDaemon) throws Exception {
        final int CHUNK_SIZE = 5 * 1024 * 1024; // 5MB chunks
        int numChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);

        File outputFile = new File(storageDirectory, filename);
        try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {
            raf.setLength(fileSize); // Pre-allocate file

            for (int i = 0; i < numChunks; i++) {
                long offset = i * (long) CHUNK_SIZE;
                int size = (int) Math.min(CHUNK_SIZE, fileSize - offset);

                byte[] chunk = sourceDaemon.downloadChunk(filename, offset, size);
                if (chunk != null && chunk.length > 0) {
                    raf.seek(offset);
                    raf.write(chunk);
                    System.out.println("Downloaded chunk " + (i+1) + "/" + numChunks +
                            " of " + filename + " (" + chunk.length + " bytes)");
                } else {
                    throw new IOException("Failed to download chunk " + i);
                }
            }
        }

        System.out.println("Successfully downloaded large file: " + filename);
        // Register the file
        directory.registerFile(filename, daemonId);
    }

    private void shutdown() {
        System.out.println("Shutting down daemon...");

        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }

        if (fileMonitorScheduler != null) {
            fileMonitorScheduler.shutdownNow();
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