package com.server.usth;

import com.server.usth.impl.Directory;
import com.server.usth.services.DaemonService;

import java.io.FileOutputStream;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class DownloadClient {
    private static final int CHUNK_SIZE = 1024 * 1024; // 1MB chunks

    public void downloadFile(String filename, String outputPath) {
        try {
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            Directory directory = (Directory) registry.lookup("Directory");

            List<DaemonService> daemons = directory.getDaemonsForFile(filename);
            if (daemons.isEmpty()) {
                throw new Exception("No daemons available for file: " + filename);
            }

            // Get file size from first daemon
            long fileSize = daemons.get(0).getFileSize(filename);
            long numChunks = (fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE;

            // Create thread pool for parallel downloads
            ExecutorService executor = Executors.newFixedThreadPool(daemons.size());
            List<Future<ChunkResult>> futures = new ArrayList<>();

            // Distribute chunks among daemons
            for (long i = 0; i < numChunks; i++) {
                final long offset = i * CHUNK_SIZE;
                final int size = (int) Math.min(CHUNK_SIZE, fileSize - offset);
                DaemonService daemon = daemons.get((int) (i % daemons.size()));

                futures.add(executor.submit(() -> {
                    byte[] data = daemon.downloadChunk(filename, offset, size);
                    return new ChunkResult(offset, data);
                }));
            }

            // Write chunks to file in order
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                for (Future<ChunkResult> future : futures) {
                    ChunkResult result = future.get();
                    fos.write(result.data);
                }
            }

            executor.shutdown();
            System.out.println("Download completed: " + filename);

        } catch (Exception e) {
            System.err.println("Download failed: " + e.toString());
            e.printStackTrace();
        }
    }

    private static class ChunkResult {
        final long offset;
        final byte[] data;

        ChunkResult(long offset, byte[] data) {
            this.offset = offset;
            this.data = data;
        }
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: DownloadClient <filename> <outputPath>");
            return;
        }

        DownloadClient client = new DownloadClient();
        client.downloadFile(args[0], args[1]);
    }
}