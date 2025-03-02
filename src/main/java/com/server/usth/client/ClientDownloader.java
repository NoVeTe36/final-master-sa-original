package com.server.usth.client;

import com.server.usth.services.Directory;
import com.server.usth.services.DaemonService;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.io.File;
import java.sql.Time;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


public class ClientDownloader extends UnicastRemoteObject implements DaemonService {
    private final String daemonId;
    private final String filename;
    private final String downloadPath;
    private Directory directory;
    private final Set<String> failedDaemons = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Long> downloadStatus = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> fragmentsPerDaemon = new ConcurrentHashMap<>();
    private final Map<String, Double> daemonSpeedMap = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Long> failedDaemonTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private final long DAEMON_RETRY_TIMEOUT = 5000; // 5 seconds timeout before retry
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    public ClientDownloader(String daemonId, String filename, String downloadPath) throws RemoteException {
        this.daemonId = daemonId;
        this.filename = filename;
        this.downloadPath = downloadPath;
    }

    private static class ChunkInfo {
        long offset;
        int size;

        ChunkInfo(long offset, int size) {
            this.offset = offset;
            this.size = size;
        }
    }

    private static class FailedChunkInfo extends ChunkInfo {
        String failedDaemonId;

        FailedChunkInfo(long offset, int size, String failedDaemonId) {
            super(offset, size);
            this.failedDaemonId = failedDaemonId;
        }
    }

    @Override
    public long getFileSize(String file) throws RemoteException {
        if (!file.equals(filename)) {
            return 0;
        }

        File localFile = new File(downloadPath, file);
        if (!localFile.exists()) {
            return 0;
        }

        return localFile.length();
    }

    @Override
    public void receiveFile(String file, byte[] data) throws RemoteException {
        System.out.println("Received file: " + file);
    }

    @Override
    public String getDaemonId() throws RemoteException {
        return daemonId;
    }

    private void notifyServerOfFailedDaemon(String daemonId) {
        try {
            System.out.println("WARNING: Daemon " + daemonId + " has failed " + MAX_CONSECUTIVE_FAILURES +
                    " consecutive times. Notifying server to remove it.");
            try {
                directory.heartbeat(daemonId + "_FAILED_" + filename);
                System.out.println("Notified server about consistently failing daemon: " + daemonId);
            } catch (Exception e) {
                System.out.println("Failed to notify server: " + e.getMessage());
            }
        } catch (Exception e) {
            System.out.println("Error handling daemon failure notification: " + e.getMessage());
        }
    }

    public void startClient() throws Exception {
        Registry registry = LocateRegistry.getRegistry("localhost", 1099);
        directory = (Directory) registry.lookup("Directory");

        Set<String> files = directory.getAvailableFiles();
        if (!files.contains(filename)) {
            System.out.println("File not available for download: " + filename);
            return;
        }

        if (!new File(downloadPath).exists()) {
            System.out.println("Download path does not exist: " + downloadPath);
            return;
        }

        if (new File(downloadPath, filename).exists()) {
            System.out.println("File already downloaded: " + filename);
            already_download();
            return;
        }
        Time startTime = new Time(System.currentTimeMillis());
        download(startTime);

        directory.registerDaemon(daemonId, this);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                directory.heartbeat(daemonId);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 10, TimeUnit.SECONDS);
    }

    public void already_download() {
        try {
            System.out.println("File already exists: " + filename + ". Registering as daemon...");

            // Register this client as a daemon for the file
            directory.registerDaemon(daemonId, this);

            directory.registerFile(filename, daemonId);

            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    directory.heartbeat(daemonId);
                } catch (Exception e) {
                    e.printStackTrace();
                    System.out.println("Failed to send heartbeat: " + e.getMessage());
                }
            }, 0, 10, TimeUnit.SECONDS);

            System.out.println("Registered as daemon for: " + filename);
        } catch (Exception e) {
            System.out.println("Failed to register as daemon: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void download(Time startTime) {
        try {
            List<DaemonService> daemons = directory.getDaemonsForFile(filename);
            if (daemons.isEmpty()) {
                System.out.println("No daemons found for file: " + filename);
                return;
            }

            long fileSize = 0;
            for (DaemonService daemon : daemons) {
                try {
                    long size = daemon.getFileSize(filename);
                    if (size > 0) {
                        fileSize = size;
                        break;
                    }
                } catch (Exception e) {
                    System.out.println("Failed to get file size from: " + daemon.getDaemonId());
                }
            }

            if (fileSize <= 0) {
                System.out.println("Could not determine file size for: " + filename);
                return;
            }

            // Determine chunk size and number of chunks
            final int CHUNK_SIZE = 1024 * 1024; // 5MB chunks
            int numChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);

            // Pre-allocate the output file
            File outputFile = new File(downloadPath, filename);
            try (FileChannel channel = new RandomAccessFile(outputFile, "rw").getChannel()) {
                channel.truncate(fileSize);
            }

            ExecutorService executor = Executors.newFixedThreadPool(daemons.size());
            CountDownLatch latch = new CountDownLatch(numChunks);

            // Track failed chunks with their offset and size
            ConcurrentMap<Integer, ChunkInfo> failedChunks = new ConcurrentHashMap<>();

            for (int i = 0; i < numChunks; i++) {
                final int chunkIndex = i;
                final long offset = chunkIndex * (long) CHUNK_SIZE;
                final int size = (int) Math.min(CHUNK_SIZE, fileSize - offset);

                executor.submit(() -> {
                    try {
                        DaemonService daemon = selectDaemonForChunk(daemons, chunkIndex);
                        String currentDaemonId = daemon.getDaemonId();

                        // Track fragment count for this daemon
                        fragmentsPerDaemon.computeIfAbsent(currentDaemonId, k -> new AtomicInteger(0))
                                .incrementAndGet();

                        System.out.println("Downloading chunk " + chunkIndex + " from: " +
                                currentDaemonId + " (offset: " + offset +
                                ", size: " + size + ")");

                        // Only download if size is valid
                        if (size > 0) {
                            long startTimeMs = System.currentTimeMillis();
                            byte[] chunk = daemon.downloadChunk(filename, offset, size);
                            long endTimeMs = System.currentTimeMillis();

                            // Measure and report speed
                            measureAndReportSpeed(currentDaemonId, chunk.length, endTimeMs - startTimeMs);

                            // Write directly to file using FileChannel
                            try (FileChannel channel = new RandomAccessFile(outputFile, "rw").getChannel()) {
                                ByteBuffer buffer = ByteBuffer.wrap(chunk);
                                channel.position(offset);
                                channel.write(buffer);
                            }

                            downloadStatus.put(currentDaemonId,
                                    downloadStatus.getOrDefault(currentDaemonId, 0L) + chunk.length);
                        }

                    } catch (Exception e) {
                        System.out.println("Error downloading chunk " + chunkIndex + ": " + e.getMessage());
                        try {
                            String failedDaemonId = selectDaemonForChunk(daemons, chunkIndex).getDaemonId();

                            // Increment consecutive failures counter
                            int failures = consecutiveFailures.getOrDefault(failedDaemonId, 0) + 1;
                            consecutiveFailures.put(failedDaemonId, failures);

                            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                                notifyServerOfFailedDaemon(failedDaemonId);
                            }

                            failedDaemonTimestamps.put(failedDaemonId, System.currentTimeMillis());
                            System.out.println("Added timeout for daemon: " + failedDaemonId +
                                    " (failure " + failures + " of " + MAX_CONSECUTIVE_FAILURES + ")");

                            // Store failed chunk info with the daemon that failed
                            failedChunks.put(chunkIndex, new FailedChunkInfo(offset, size, failedDaemonId));
                        } catch (Exception ex) {
                            System.out.println("Could not get daemon ID: " + ex.getMessage());
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.MINUTES);
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.MINUTES);

            Time endTime = new Time(System.currentTimeMillis());
            System.out.println("Download time: " + (endTime.getTime() - startTime.getTime()) + " ms");

            // Retry failed downloads if any
            if (!failedChunks.isEmpty()) {
                System.out.println("\n=== RETRYING " + failedChunks.size() + " FAILED CHUNKS ===");
                retryFailedChunks(daemons, outputFile, failedChunks);
            }

            // Print download statistics
            System.out.println("\n=== DOWNLOAD STATISTICS ===");
            System.out.println("Fragments handled by each daemon:");
            fragmentsPerDaemon.forEach((daemonId, count) -> {
                double speed = daemonSpeedMap.getOrDefault(daemonId, 0.0);
                System.out.printf("- %s: %d fragments (%.2f KB/s)%n",
                        daemonId, count.get(), speed);
            });
            System.out.println("===========================\n");

            directory.registerFile(filename, daemonId);
            System.out.println("Registered file: " + filename + " for daemon: " + daemonId);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

//    // this is the download without balancing and with file channel
//    public void download(Time startTime) {
//        try {
//            List<DaemonService> daemons = directory.getDaemonsForFile(filename);
//            if (daemons.isEmpty()) {
//                System.out.println("No daemons found for file: " + filename);
//                return;
//            }
//
//            // Get file size
//            long fileSize = 0;
//            for (DaemonService daemon : daemons) {
//                try {
//                    long size = daemon.getFileSize(filename);
//                    if (size > 0) {
//                        fileSize = size;
//                        break;
//                    }
//                } catch (Exception e) {
//                    System.out.println("Failed to get file size from: " + daemon.getDaemonId());
//                }
//            }
//
//            if (fileSize <= 0) {
//                System.out.println("Could not determine file size for: " + filename);
//                return;
//            }
//
//            // Determine chunk size and number of chunks
//            final int CHUNK_SIZE = 1024 * 1024; // 5MB chunks
//            int numChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
//
//            // Pre-allocate the output file
//            File outputFile = new File(downloadPath, filename);
//            try (FileChannel channel = new RandomAccessFile(outputFile, "rw").getChannel()) {
//                channel.truncate(fileSize);
//            }
//
//            ExecutorService executor = Executors.newFixedThreadPool(daemons.size());
//            CountDownLatch latch = new CountDownLatch(numChunks);
//            final ConcurrentMap<String, AtomicInteger> fragmentsPerDaemon = new ConcurrentHashMap<>();
//
//            for (int i = 0; i < numChunks; i++) {
//                final int chunkIndex = i;
//                final long offset = chunkIndex * (long) CHUNK_SIZE;
//                final int size = (int) Math.min(CHUNK_SIZE, fileSize - offset);
//
//                executor.submit(() -> {
//                    try {
//                        DaemonService daemon = daemons.get(chunkIndex % daemons.size());
//                        String currentDaemonId = daemon.getDaemonId();
//
//                        fragmentsPerDaemon.computeIfAbsent(currentDaemonId, k -> new AtomicInteger(0))
//                                .incrementAndGet();
//
//                        System.out.println("Downloading chunk " + chunkIndex + " from: " +
//                                currentDaemonId + " (offset: " + offset +
//                                ", size: " + size + ")");
//
//                        if (size > 0) {
//                            long startTimeMs = System.currentTimeMillis();
//                            byte[] chunk = daemon.downloadChunk(filename, offset, size);
//                            long endTimeMs = System.currentTimeMillis();
//                            long downloadTimeMs = endTimeMs - startTimeMs;
//
//                            measureAndReportSpeed(currentDaemonId, chunk.length, downloadTimeMs);
//
//                            System.out.println("Downloaded chunk " + chunkIndex + " in " +
//                                    downloadTimeMs + "ms from " + currentDaemonId);
//
//                            try (FileChannel channel = new RandomAccessFile(outputFile, "rw").getChannel()) {
//                                ByteBuffer buffer = ByteBuffer.wrap(chunk);
//                                channel.position(offset);
//                                channel.write(buffer);
//                            }
//
//                            downloadStatus.put(currentDaemonId,
//                                    downloadStatus.getOrDefault(currentDaemonId, 0L) + chunk.length);
//                        }
//                    } catch (Exception e) {
//                        System.out.println("Error downloading chunk " + chunkIndex + ": " + e.getMessage());
//                    } finally {
//                        latch.countDown();
//                    }
//                });
//            }
//
//            latch.await(10, TimeUnit.MINUTES);
//            executor.shutdown();
//            executor.awaitTermination(10, TimeUnit.MINUTES);
//
//            Time endTime = new Time(System.currentTimeMillis());
//            System.out.println("Download time: " + (endTime.getTime() - startTime.getTime()) + " ms");
//
//            System.out.println("\n=== DOWNLOAD STATISTICS ===");
//            System.out.println("Fragments handled by each daemon:");
//            fragmentsPerDaemon.forEach((daemonId, count) -> {
//                double speed = daemonSpeedMap.getOrDefault(daemonId, 0.0);
//                System.out.printf("- %s: %d fragments (%.2f KB/s)%n",
//                        daemonId, count.get(), speed);
//            });
//            System.out.println("===========================\n");
//
//            directory.registerFile(filename, daemonId);
//
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }

    private void measureAndReportSpeed(String daemonId, long bytesDownloaded, long timeMs) {
        try {
            if (timeMs > 0 && bytesDownloaded > 0) {
                double speedKBps = (bytesDownloaded / 1024.0) / (timeMs / 1000.0);

                double currentSpeed = daemonSpeedMap.getOrDefault(daemonId, speedKBps);
                double newSpeed = (currentSpeed * 0.7) + (speedKBps * 0.3); // Exponential moving average
                daemonSpeedMap.put(daemonId, newSpeed);

                directory.reportDaemonSpeed(daemonId, speedKBps);
                System.out.println("Measured speed for " + daemonId + ": " +
                        String.format("%.2f", speedKBps) + " KB/s");
            }
        } catch (Exception e) {
            System.out.println("Failed to report daemon speed: " + e.getMessage());
        }
    }

    private void retryFailedChunks(List<DaemonService> daemons, File outputFile,
                                   ConcurrentMap<Integer, ChunkInfo> failedChunks) {
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(daemons.size(), failedChunks.size()));
        CountDownLatch latch = new CountDownLatch(failedChunks.size());

        failedChunks.forEach((chunkIndex, chunkInfo) -> {
            executor.submit(() -> {
                try {
                    // Filter out daemon that failed this chunk
                    String failedId = (chunkInfo instanceof FailedChunkInfo) ?
                            ((FailedChunkInfo) chunkInfo).failedDaemonId : null;
                    List<DaemonService> available = daemons.stream()
                            .filter(d -> {
                                try {
                                    return failedId == null || !d.getDaemonId().equals(failedId);
                                } catch (RemoteException e) {
                                    return false;
                                }
                            })
                            .collect(Collectors.toList());

                    if (available.isEmpty()) {
                        System.out.println("No alternative daemons available, must use original list");
                        available = daemons;
                    }

                    // Use selection logic on the filtered list
                    DaemonService daemon = selectDaemonForChunk(available, chunkIndex);
                    byte[] chunk = daemon.downloadChunk(filename, chunkInfo.offset, chunkInfo.size);

                    // Check for partial chunk
                    if (chunk.length < chunkInfo.size && chunk.length > 0) {
                        int remainingSize = chunkInfo.size - chunk.length;
                        long newOffset = chunkInfo.offset + chunk.length;
                        System.out.println("Partial chunk detected at chunk " + chunkIndex
                                + ". Retrying remainder offset=" + newOffset
                                + ", size=" + remainingSize);
                        failedChunks.put(chunkIndex, new ChunkInfo(newOffset, remainingSize));
                    } else {
                        // Write chunk to file if it's full or zero
                        try (FileChannel channel = new RandomAccessFile(outputFile, "rw").getChannel()) {
                            ByteBuffer buffer = ByteBuffer.wrap(chunk);
                            channel.position(chunkInfo.offset);
                            channel.write(buffer);
                            System.out.println("Successfully retried chunk " + chunkIndex);
                            fragmentsPerDaemon.computeIfAbsent(daemon.getDaemonId(), k -> new AtomicInteger(0))
                                    .incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    System.out.println("Retry failed for chunk " + chunkIndex + ": " + e.getMessage());
                    try {
                        String failedDaemonId = selectDaemonForChunk(daemons, chunkIndex).getDaemonId();
                        int failures = consecutiveFailures.getOrDefault(failedDaemonId, 0) + 1;
                        consecutiveFailures.put(failedDaemonId, failures);
                        if (failures >= MAX_CONSECUTIVE_FAILURES) {
                            notifyServerOfFailedDaemon(failedDaemonId);
                        }
                        failedDaemonTimestamps.put(failedDaemonId, System.currentTimeMillis());
                    } catch (Exception ex) {
                        System.out.println("Could not get daemon ID in retry: " + ex.getMessage());
                    }
                } finally {
                    latch.countDown();
                }
            });
        });

        try {
            latch.await(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            System.out.println("Retry operation interrupted");
        }
        executor.shutdown();
    }

    @Override
    public byte[] downloadChunk(String file, long offset, int size) throws RemoteException {
        if (!file.equals(filename)) {
            System.out.println("[" + daemonId + "] Requested file not available: " + file);
            return new byte[0];
        }

        File localFile = new File(downloadPath, file);
        if (!localFile.exists()) {
            System.out.println("[" + daemonId + "] File not found: " + file);
            return new byte[0];
        }

        try (RandomAccessFile raf = new RandomAccessFile(localFile, "r")) {
            if (raf.length() < offset) {
                System.out.println("[" + daemonId + "] Requested offset beyond file size: " + offset + " > " + raf.length());
                return new byte[0];
            }

            raf.seek(offset);

            int bytesToRead = (int) Math.min(size, raf.length() - offset);
            byte[] chunk = new byte[bytesToRead];

            // Read the data
            int bytesRead = raf.read(chunk);

            if (bytesRead < 0) {
                System.out.println("[" + daemonId + "] End of file reached unexpectedly");
                return new byte[0];
            } else if (bytesRead < bytesToRead) {
                // Partial read, return only what was read
                byte[] partial = new byte[bytesRead];
                System.arraycopy(chunk, 0, partial, 0, bytesRead);
                System.out.println("[" + daemonId + "] Partial read: " + bytesRead + "/" + bytesToRead + " bytes");
                return partial;
            }

            System.out.println("[" + daemonId + "] Successfully served chunk: offset=" + offset + ", size=" + bytesRead);
            return chunk;
        } catch (Exception e) {
            System.out.println("[" + daemonId + "] Error reading file: " + e.getMessage());
            throw new RemoteException("Chunk read error: " + e.getMessage(), e);
        }
    }

    private DaemonService selectDaemonForChunk(List<DaemonService> daemons, int chunkIndex) throws RemoteException {
        // Filter out temporarily unavailable daemons
        List<DaemonService> availableDaemons = new ArrayList<>();
        long currentTime = System.currentTimeMillis();

        for (DaemonService daemon : daemons) {
            String id = daemon.getDaemonId();
            Long failTime = failedDaemonTimestamps.get(id);

            // Include daemon if not failed or if retry timeout has passed
            if (failTime == null || (currentTime - failTime) > DAEMON_RETRY_TIMEOUT) {
                availableDaemons.add(daemon);
                if (failTime != null) {
                    failedDaemonTimestamps.remove(id);
                    // Reset consecutive failures when daemon becomes available again
                    consecutiveFailures.remove(id);
                    System.out.println("Daemon " + id + " is available again after timeout");
                }
            }
        }

        // If all daemons are in timeout, use the least recently failed one
        if (availableDaemons.isEmpty()) {
            System.out.println("All daemons are in timeout, using least recently failed daemon");
            String leastRecentId = failedDaemonTimestamps.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);

            if (leastRecentId != null) {
                for (DaemonService d : daemons) {
                    try {
                        if (d.getDaemonId().equals(leastRecentId)) {
                            return d;
                        }
                    } catch (RemoteException e) {
                        continue;
                    }
                }
            }
            return daemons.get(0);
        }

        // Use speed-based selection for available daemons
        if (chunkIndex < availableDaemons.size() || daemonSpeedMap.isEmpty()) {
            return availableDaemons.get(chunkIndex % availableDaemons.size());
        }

        // When we have enough data, use weighted selection
        double totalWeight = 0;
        for (DaemonService daemon : availableDaemons) {
            String id;
            try {
                id = daemon.getDaemonId();
                double speed = daemonSpeedMap.getOrDefault(id, 1.0);
                if (speed <= 0) speed = 0.1;
                totalWeight += speed;
            } catch (RemoteException e) {
                continue;
            }
        }

        double random = Math.random() * totalWeight;
        double weightSum = 0;

        for (DaemonService daemon : availableDaemons) {
            try {
                String id = daemon.getDaemonId();
                double speed = daemonSpeedMap.getOrDefault(id, 1.0);
                if (speed <= 0) speed = 0.1;

                weightSum += speed;
                if (random <= weightSum) {
                    System.out.println("Selected daemon " + id + " with speed " + speed + " KB/s");
                    return daemon;
                }
            } catch (RemoteException e) {
                continue;
            }
        }

        return availableDaemons.get(0);
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