package com.server.usth.client;

import com.server.usth.impl.Directory;
import com.server.usth.services.DaemonService;

import java.io.File;
import java.io.FileOutputStream;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClientDownloader {
    private static final int THREAD_POOL_SIZE = 4; // Number of parallel downloads
    private final String filename;
    private final String downloadPath;

    public ClientDownloader(String filename, String downloadPath) {
        this.filename = filename;
        this.downloadPath = downloadPath;
    }

    public void download() {
        try {
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            Directory directory = (Directory) registry.lookup("Directory");

            // Get daemons storing this file
            List<DaemonService> daemons = directory.getDaemonsForFile(filename);
            if (daemons.isEmpty()) {
                System.out.println("No daemons found for file: " + filename);
                return;
            }

            // Get file size from first available daemon
            long fileSize = daemons.get(0).getFileSize(filename);
            int chunkSize = (int) Math.ceil((double) fileSize / daemons.size());

            System.out.println("Downloading " + filename + " (" + fileSize + " bytes) from " + daemons.size() + " daemons.");

            ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

            // Download chunks in parallel
            byte[][] fileChunks = new byte[daemons.size()][];
            for (int i = 0; i < daemons.size(); i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        DaemonService daemon = daemons.get(index);
                        fileChunks[index] = daemon.downloadChunk(filename, index * chunkSize, chunkSize);
                        System.out.println("Downloaded chunk " + index + " from daemon.");
                    } catch (Exception e) {
                        System.err.println("Failed to download chunk " + index + ": " + e.getMessage());
                    }
                });
            }

            executor.shutdown();
            while (!executor.isTerminated()) {
                Thread.sleep(500);
            }

            // Merge chunks
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
            System.out.println("Download completed: " + filename);
        } catch (Exception e) {
            System.err.println("Error merging chunks: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: java ClientDownloader <filename> <downloadPath>");
            return;
        }

        ClientDownloader client = new ClientDownloader(args[0], args[1]);
        client.download();
    }
}
