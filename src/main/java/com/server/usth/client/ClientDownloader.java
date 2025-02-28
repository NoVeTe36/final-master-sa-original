package com.server.usth.client;

import com.server.usth.impl.Directory;
import com.server.usth.services.DaemonService;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.*;

public class ClientDownloader extends UnicastRemoteObject implements DaemonService {
    private final String daemonId;
    private final String filename;
    private final String downloadPath;
    private Directory directory;

    public ClientDownloader(String daemonId, String filename, String downloadPath) throws RemoteException {
        this.daemonId = daemonId;
        this.filename = filename;
        this.downloadPath = downloadPath;
    }

    public void startClient() throws Exception {
        Registry registry = LocateRegistry.getRegistry("localhost", 1099);
        directory = (Directory) registry.lookup("Directory");

        // Download logic
        download();

        // Register this client as a daemon after download
        directory.registerDaemon(daemonId, this);

        // Start heartbeat
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                directory.heartbeat(daemonId);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 10, TimeUnit.SECONDS);
    }

    public void download() {
        try {
            List<DaemonService> daemons = directory.getDaemonsForFile(filename);
            if (daemons.isEmpty()) {
                System.out.println("No daemons found for file: " + filename);
                return;
            }

            // Get file size from the first daemon that returns a valid size
            long fileSize = 0;
            for (DaemonService daemon : daemons) {
                try {
                    long size = daemon.getFileSize(filename);
                    if (size > 0) {
                        fileSize = size;
                        break;
                    }
                } catch (Exception e) {
                    // Continue to next daemon
                }
            }

            if (fileSize <= 0) {
                System.out.println("Could not determine file size for: " + filename);
                return;
            }

            // Determine chunk size and distribution
            int totalDaemons = daemons.size();
            int chunkSize = (int) Math.ceil((double) fileSize / totalDaemons);

            ExecutorService executor = Executors.newFixedThreadPool(totalDaemons);
            byte[][] fileChunks = new byte[totalDaemons][];

            CountDownLatch latch = new CountDownLatch(totalDaemons);

            for (int i = 0; i < totalDaemons; i++) {
                final int index = i;
                final long offset = index * (long)chunkSize;
                final int size = (int) Math.min(chunkSize, fileSize - offset);

                executor.submit(() -> {
                    try {
                        DaemonService daemon = daemons.get(index);
                        System.out.println("Downloading chunk " + index + " from: " +
                                daemon.getDaemonId() + " (offset: " + offset +
                                ", size: " + size + ")");

                        // Only download if size is valid
                        if (size > 0) {
                            fileChunks[index] = daemon.downloadChunk(filename, offset, size);
                        } else {
                            fileChunks[index] = new byte[0];
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Wait for all downloads to complete
            latch.await(1, TimeUnit.MINUTES);
            executor.shutdown();

            mergeChunks(fileChunks);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void mergeChunks(byte[][] fileChunks) {
        try (FileOutputStream fos = new FileOutputStream(new File(downloadPath, filename))) {
            for (byte[] chunk : fileChunks) {
                if (chunk != null) {
                    fos.write(chunk);
                }
            }
            System.out.println("Download complete: " + filename);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public byte[] downloadChunk(String file, long offset, int size) throws RemoteException {
        return null;
    }

    @Override
    public long getFileSize(String file) throws RemoteException {
        return 0L;
    }

    @Override
    public void receiveFile(String file, byte[] data) throws RemoteException {
    }

    @Override
    public String getDaemonId() throws RemoteException {
        return daemonId;
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("Usage: java ClientDownloader <daemonId> <filename> <downloadPath>");
            return;
        }
        try {
            ClientDownloader client = new ClientDownloader(args[0], args[1], args[2]);
            client.startClient();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}