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
    private final String clientId;
    private final String filename;
    private final String downloadPath;
    private Directory directory;

    public ClientDownloader(String clientId, String filename, String downloadPath) throws RemoteException {
        this.clientId = clientId;
        this.filename = filename;
        this.downloadPath = downloadPath;
    }

    public void startClient() throws Exception {
        Registry registry = LocateRegistry.getRegistry("localhost", 1099);
        directory = (Directory) registry.lookup("Directory");

        String actualClientId = (clientId == null || clientId.isEmpty() || clientId.equals("auto"))
                ? directory.getUniqueClientId()
                : clientId;

        // Register this as a client instead of a daemon
        directory.registerClient(actualClientId, this);

        // Start heartbeat
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                directory.heartbeat(actualClientId);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 10, TimeUnit.SECONDS);

        // Download logic
        download();

        // Optional: Unregister after download complete
        // directory.unregisterClient(actualClientId);

        // Shut down the heartbeat scheduler
        scheduler.shutdown();
    }

    public void download() {
        try {
            List<DaemonService> daemons = directory.getDaemonsForFile(filename);
            if (daemons.isEmpty()) {
                System.out.println("No daemons found for file: " + filename);
                return;
            }
            long fileSize = daemons.get(0).getFileSize(filename);
            int chunkSize = (int) Math.ceil((double) fileSize / daemons.size());
            ExecutorService executor = Executors.newFixedThreadPool(4);
            byte[][] fileChunks = new byte[daemons.size()][];

            for (int i = 0; i < daemons.size(); i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        DaemonService daemon = daemons.get(index);
                        System.out.println("Downloading chunk " + index + " from daemon: " + daemon.getDaemonId());
                        fileChunks[index] = daemon.downloadChunk(filename, index * chunkSize, chunkSize);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.MINUTES);

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
        // Not syncing, return empty array
        return new byte[0];
    }

    @Override
    public long getFileSize(String file) throws RemoteException {
        return 0L; // Not syncing
    }

    @Override
    public void receiveFile(String file, byte[] data) throws RemoteException {
        // Clients don't need to receive files for syncing
    }

    @Override
    public String getDaemonId() throws RemoteException {
        return clientId;
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.out.println("Usage: java ClientDownloader [clientId] <filename> <downloadPath>");
            return;
        }

        try {
            ClientDownloader client;
            if (args.length == 2) {
                // Auto-generate client ID
                client = new ClientDownloader("auto", args[0], args[1]);
            } else {
                client = new ClientDownloader(args[0], args[1], args[2]);
            }
            client.startClient();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}