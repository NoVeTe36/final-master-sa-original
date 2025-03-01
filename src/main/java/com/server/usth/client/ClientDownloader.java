package com.server.usth.client;

import com.server.usth.services.Directory;
import com.server.usth.services.DaemonService;

import java.io.RandomAccessFile;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.io.File;
import java.io.FileOutputStream;
import java.sql.Time;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.lang.Thread.sleep;

public class ClientDownloader extends UnicastRemoteObject implements DaemonService {
    private final String daemonId;
    private final String filename;
    private final String downloadPath;
    private Directory directory;
    private final Set<String> failedDaemons = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Long> downloadStatus = new ConcurrentHashMap<>();

    public ClientDownloader(String daemonId, String filename, String downloadPath) throws RemoteException {
        this.daemonId = daemonId;
        this.filename = filename;
        this.downloadPath = downloadPath;
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
            final int CHUNK_SIZE = 5 * 1024 * 1024; // 5MB chunks
            int numChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);

            ExecutorService executor = Executors.newFixedThreadPool(daemons.size());
            byte[][] fileChunks = new byte[numChunks][];

            CountDownLatch latch = new CountDownLatch(numChunks);

            for (int i = 0; i < numChunks; i++) {
                final int chunkIndex = i;
                final long offset = chunkIndex * (long) CHUNK_SIZE;
                final int size = (int) Math.min(CHUNK_SIZE, fileSize - offset);

                executor.submit(() -> {
                    try {
                        DaemonService daemon = daemons.get(chunkIndex % daemons.size());
                        String currentDaemonId = daemon.getDaemonId();
                        System.out.println("Downloading chunk " + chunkIndex + " from: " +
                                currentDaemonId + " (offset: " + offset +
                                ", size: " + size + ")");

                        // Only download if size is valid
                        if (size > 0) {
                            byte[] chunk = daemon.downloadChunk(filename, offset, size);
                            fileChunks[chunkIndex] = chunk;
                            downloadStatus.put(currentDaemonId, (long) chunk.length);
                        } else {
                            fileChunks[chunkIndex] = new byte[0];
                        }
                    } catch (Exception e) {
                        try {
                            String failedDaemonId = daemons.get(chunkIndex % daemons.size()).getDaemonId();
                            System.out.println("*** ERROR: Daemon " + failedDaemonId +
                                    " crashed during download of chunk " + chunkIndex + " ***");
                            System.out.println("Error message: " + e.getMessage());
                            failedDaemons.add(failedDaemonId);
                        } catch (RemoteException ex) {
                            System.out.println("Failed to identify daemon for chunk " + chunkIndex);
                            ex.printStackTrace();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(1, TimeUnit.MINUTES);
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.MINUTES);

            Time endTime = new Time(System.currentTimeMillis());
            System.out.println("Download time: " + (endTime.getTime() - startTime.getTime()) + " ms");

            mergeChunks(fileChunks);

            // Retry failed downloads
            if (!failedDaemons.isEmpty()) {
                System.out.println("\n=== RETRYING DOWNLOADS FOR FAILED DAEMONS ===");
                System.out.println("Failed daemons: " + failedDaemons);
                retryFailedDownloads(daemons, fileSize, CHUNK_SIZE);
            }

            directory.registerFile(filename, daemonId);
            System.out.println("Registered file: " + filename + " for daemon: " + daemonId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

//    public void download(Time startTime) {
//        try {
//            List<DaemonService> daemons = directory.getDaemonsForFile(filename);
//            if (daemons.isEmpty()) {
//                System.out.println("No daemons found for file: " + filename);
//                return;
//            }
//
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
//            final int CHUNK_SIZE = 5 * 1024 * 1024; // 5MB chunks
//            int numChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
//
//            ExecutorService executor = Executors.newFixedThreadPool(daemons.size());
//            byte[][] fileChunks = new byte[numChunks][];
//
//            CountDownLatch latch = new CountDownLatch(numChunks);
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
//                        System.out.println("Downloading chunk " + chunkIndex + " from: " +
//                                currentDaemonId + " (offset: " + offset +
//                                ", size: " + size + ")");
//
//                        // Only download if size is valid
//                        if (size > 0) {
//                            byte[] chunk = daemon.downloadChunk(filename, offset, size);
//                            Time endTime = new Time(System.currentTimeMillis());
//
//                            // Measure and report speed
//                            measureAndReportSpeed(currentDaemonId, chunk.length, endTime.getTime() - startTime.getTime());
//
//                            fileChunks[chunkIndex] = chunk;
//                            downloadStatus.put(currentDaemonId, (long) chunk.length);
//                        } else {
//                            fileChunks[chunkIndex] = new byte[0];
//                        }
//                    } catch (Exception e) {
//                        // Error handling code remains the same
//                    } finally {
//                        latch.countDown();
//                    }
//                });
//            }
//
//            latch.await(1, TimeUnit.MINUTES);
//            executor.shutdown();
//            executor.awaitTermination(1, TimeUnit.MINUTES);
//
//            Time endTime = new Time(System.currentTimeMillis());
//            System.out.println("Download time: " + (endTime.getTime() - startTime.getTime()) + " ms");
//
//            mergeChunks(fileChunks);
//
//            // Retry failed downloads
//            if (!failedDaemons.isEmpty()) {
//                System.out.println("\n=== RETRYING DOWNLOADS FOR FAILED DAEMONS ===");
//                System.out.println("Failed daemons: " + failedDaemons);
//                retryFailedDownloads(daemons, fileSize, CHUNK_SIZE);
//            }
//
//            directory.registerFile(filename, daemonId);
//            System.out.println("Registered file: " + filename + " for daemon: " + daemonId);
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }
//
//
//    private void measureAndReportSpeed(String daemonId, long bytesDownloaded, long timeMs) {
//        try {
//            if (timeMs > 0 && bytesDownloaded > 0) {
//                double speedKBps = (bytesDownloaded / 1024.0) / (timeMs / 1000.0);
//                directory.reportDaemonSpeed(daemonId, speedKBps);
//                System.out.println("Measured speed for " + daemonId + ": " +
//                        String.format("%.2f", speedKBps) + " KB/s");
//            }
//        } catch (Exception e) {
//            System.out.println("Failed to report daemon speed: " + e.getMessage());
//        }
//    }

    private void retryFailedDownloads(List<DaemonService> daemons, long fileSize, int chunkSize) {
        if (failedDaemons.isEmpty()) {
            return;
        }

        System.out.println("\n=== RETRYING DOWNLOADS FOR FAILED DAEMONS IN PARALLEL ===");
        System.out.println("Failed daemons: " + failedDaemons);

        // Find available daemons (excluding failed ones)
        List<DaemonService> availableDaemons = daemons.stream()
                .filter(d -> {
                    try {
                        return !failedDaemons.contains(d.getDaemonId());
                    } catch (RemoteException e) {
                        return false;
                    }
                })
                .collect(Collectors.toList());

        if (availableDaemons.isEmpty()) {
            System.out.println("No available daemons for retry!");
            return;
        }

        // Create tasks and execute them in parallel
        ExecutorService executor = Executors.newFixedThreadPool(failedDaemons.size());
        CountDownLatch latch = new CountDownLatch(failedDaemons.size());

        for (String failedDaemonId : failedDaemons) {
            executor.submit(() -> {
                try {
                    // Find the index of the failed daemon to determine chunk offset
                    int daemonIndex = -1;
                    for (int i = 0; i < daemons.size(); i++) {
                        try {
                            if (daemons.get(i).getDaemonId().equals(failedDaemonId)) {
                                daemonIndex = i;
                                break;
                            }
                        } catch (RemoteException e) {
                            // Skip this daemon
                        }
                    }

                    if (daemonIndex >= 0) {
                        // Calculate chunk parameters
                        long offset = daemonIndex * (long)chunkSize;
                        int size = (int) Math.min(chunkSize, fileSize - offset);

                        // Choose a random available daemon
                        DaemonService alternativeDaemon = availableDaemons.get(
                                (int)(Math.random() * availableDaemons.size()));
                        String alternativeId = alternativeDaemon.getDaemonId();

                        System.out.println("PARALLEL RETRY: Chunk " + daemonIndex +
                                " (failed daemon: " + failedDaemonId +
                                ") using alternative daemon: " + alternativeId);

                        if (size > 0) {
                            byte[] chunk = alternativeDaemon.downloadChunk(filename, offset, size);

                            synchronized (this) {
                                try (RandomAccessFile raf = new RandomAccessFile(new File(downloadPath, filename), "rw")) {
                                    raf.seek(offset);
                                    raf.write(chunk);
                                    System.out.println("Successfully recovered chunk " + daemonIndex +
                                            " with " + alternativeId + " (" + chunk.length + " bytes)");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("✗ Retry failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(1, TimeUnit.MINUTES);
            System.out.println("=== PARALLEL RETRY OPERATIONS COMPLETED ===");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        executor.shutdown();
    }

    private void mergeChunks(byte[][] fileChunks) {
        try (FileOutputStream fos = new FileOutputStream(new File(downloadPath, filename))) {
            for (byte[] chunk : fileChunks) {
                if (chunk != null) {
                    fos.write(chunk);
                }
            }
            sleep(1000);
            System.out.println("File downloaded to: " + downloadPath + "/" + filename);
        } catch (Exception e) {
            e.printStackTrace();
        }
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

    @Override
    public long getFileSize(String file) throws RemoteException {
        // Only serve the file we've downloaded
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