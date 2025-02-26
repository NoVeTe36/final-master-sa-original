//package com.server.usth.client;
//
//import com.server.usth.impl.Directory;
//import com.server.usth.services.DaemonService;
//import jakarta.xml.bind.DatatypeConverter;
//
//import java.io.File;
//import java.io.FileOutputStream;
//import java.io.IOException;
//import java.io.RandomAccessFile;
//import java.rmi.registry.LocateRegistry;
//import java.rmi.registry.Registry;
//import java.security.MessageDigest;
//import java.security.NoSuchAlgorithmException;
//import java.util.*;
//import java.util.concurrent.Callable;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//import java.util.concurrent.Future;
//
//public class P2PClient {
//    private static final int CHUNK_SIZE = 1024 * 1024; // 1MB chunks
//    private final Directory directory;
//    private final String downloadDirectory;
//    private final ExecutorService executor;
//
//    public P2PClient(String downloadDirectory, int threads) throws Exception {
//        Registry registry = LocateRegistry.getRegistry("localhost", 1099);
//        this.directory = (Directory) registry.lookup("Directory");
//        this.downloadDirectory = downloadDirectory;
//        this.executor = Executors.newFixedThreadPool(threads);
//
//        // Create download directory if it doesn't exist
//        File dir = new File(downloadDirectory);
//        if (!dir.exists()) {
//            dir.mkdirs();
//        }
//    }
//
//    public Set<String> listAvailableFiles() throws Exception {
//        return directory.getAvailableFiles();
//    }
//
//    public boolean downloadFile(String filename) throws Exception {
//        // Get daemons that have the file
//        List<DaemonService> availableDaemons = directory.getDaemonsForFile(filename);
//        if (availableDaemons.isEmpty()) {
//            System.out.println("No daemons have the requested file: " + filename);
//            return false;
//        }
//
//        // Get file metadata
//        Map<String, String> metadata = directory.getFileMetadata(filename);
//        String expectedHash = metadata.getOrDefault("hash", "");
//
//        // Get the file size from one of the daemons
//        long fileSize = availableDaemons.get(0).getFileSize(filename);
//
//        // Create a temporary file
//        String tempFilePath = downloadDirectory + "/" + filename + ".part";
//        RandomAccessFile outputFile = new RandomAccessFile(tempFilePath, "rw");
//        outputFile.setLength(fileSize);
//
//        // Calculate number of chunks
//        int numChunks = (int) ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
//
//        // Create a list of chunk download tasks
//        List<Callable<Boolean>> downloadTasks = new ArrayList<>();
//        for (int i = 0; i < numChunks; i++) {
//            final int chunkIndex = i;
//            final long offset = (long) i * CHUNK_SIZE;
//            final int size = (int) Math.min(CHUNK_SIZE, fileSize - offset);
//
//            downloadTasks.add(() -> {
//                // Try different daemons until successful
//                for (DaemonService daemon : availableDaemons) {
//                    try {
//                        if (daemon.hasChunk(filename, chunkIndex)) {
//                            byte[] chunkData = daemon.downloadChunk(filename, offset, size);
//                            synchronized (outputFile) {
//                                outputFile.seek(offset);
//                                outputFile.write(chunkData);
//                            }
//                            System.out.printf("Downloaded chunk %d/%d (%.1f%%)\n",
//                                    chunkIndex + 1, numChunks, (chunkIndex + 1) * 100.0 / numChunks);
//                            return true;
//                        }
//                    } catch (Exception e) {
//                        System.err.println("Error downloading chunk " + chunkIndex +
//                                " from daemon: " + e.getMessage());
//                    }
//                }
//                return false;
//            });
//        }
//
//        // Execute all download tasks
//        List<Future<Boolean>> results = executor.invokeAll(downloadTasks);
//
//        // Check if all chunks were downloaded successfully
//        boolean allSuccessful = true;
//        for (Future<Boolean> result : results) {
//            if (!result.get()) {
//                allSuccessful = false;
//                break;
//            }
//        }
//
//        // Close the output file
//        outputFile.close();
//
//        if (allSuccessful) {
//            // Verify file hash
//            String actualHash = calculateFileHash(tempFilePath);
//            if (!expectedHash.isEmpty() && !actualHash.equals(expectedHash)) {
//                System.out.println("File hash verification failed!");
//                new File(tempFilePath).delete();
//                return false;
//            }
//
//            // Rename temp file to final name
//            File tempFile = new File(tempFilePath);
//            File finalFile = new File(downloadDirectory + "/" + filename);
//            if (finalFile.exists()) {
//                finalFile.delete();
//            }
//            return tempFile.renameTo(finalFile);
//        } else {
//            System.out.println("Failed to download all chunks for " + filename);
//            new File(tempFilePath).delete();
//            return false;
//        }
//    }
//
//    public boolean uploadFile(String filePath) throws Exception {
//        File file = new File(filePath);
//        if (!file.exists() || !file.isFile()) {
//            System.out.println("File not found: " + filePath);
//            return false;
//        }
//
//        String filename = file.getName();
//        long fileSize = file.length();
//
//        // Calculate file hash
//        String fileHash = calculateFileHash(filePath);
//
//        // Register file with directory
//        List<DaemonService> availableDaemons = directory.getDaemonsForFile(filename);
//        if (availableDaemons.isEmpty()) {
//            System.out.println("No daemons available to upload to");
//            return false;
//        }
//
//        // Choose a random daemon to upload to
//        Random random = new Random();
//        DaemonService targetDaemon = availableDaemons.get(random.nextInt(availableDaemons.size()));
//
//        // Split file into chunks and upload
//        try (RandomAccessFile inputFile = new RandomAccessFile(file, "r")) {
//            int numChunks = (int) ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
//
//            for (int i = 0; i < numChunks; i++) {
//                long offset = (long) i * CHUNK_SIZE;
//                int size = (int) Math.min(CHUNK_SIZE, fileSize - offset);
//
//                byte[] buffer = new byte[size];
//                inputFile.seek(offset);
//                inputFile.read(buffer);
//
//                targetDaemon.uploadChunk(filename, i, buffer);
//                System.out.printf("Uploaded chunk %d/%d (%.1f%%)\n",
//                        i + 1, numChunks, (i + 1) * 100.0 / numChunks);
//            }
//        }
//
//        return true;
//    }
//
//    private String calculateFileHash(String filePath) {
//        try {
//            MessageDigest md = MessageDigest.getInstance("SHA-256");
//            try (RandomAccessFile file = new RandomAccessFile(filePath, "r")) {
//                byte[] buffer = new byte[8192];
//                int bytesRead;
//                while ((bytesRead = file.read(buffer)) != -1) {
//                    md.update(buffer, 0, bytesRead);
//                }
//            }
//            byte[] hash = md.digest();
//            return DatatypeConverter.printHexBinary(hash);
//        } catch (IOException | NoSuchAlgorithmException e) {
//            System.err.println("Error calculating hash: " + e.getMessage());
//            return "";
//        }
//    }
//
//    public void shutdown() {
//        executor.shutdown();
//    }
//
//    public static void main(String[] args) {
//        if (args.length < 1) {
//            System.out.println("Usage: P2PClient <downloadDirectory> [option] [args]");
//            System.out.println("Options:");
//            System.out.println("  list              - List available files");
//            System.out.println("  download <file>   - Download a file");
//            System.out.println("  upload <file>     - Upload a file");
//            return;
//        }
//
//        String downloadDir = args[0];
//        P2PClient client = null;
//
//        try {
//            client = new P2PClient(downloadDir, 10);
//
//            if (args.length == 1) {
//                // Interactive mode
//                Scanner scanner = new Scanner(System.in);
//                while (true) {
//                    System.out.println("\nP2P Client Commands:");
//                    System.out.println("1. List available files");
//                    System.out.println("2. Download a file");
//                    System.out.println("3. Upload a file");
//                    System.out.println("4. Exit");
//                    System.out.print("Enter choice: ");
//
//                    String choice = scanner.nextLine();
//
//                    switch (choice) {
//                        case "1":
//                            Set<String> files = client.listAvailableFiles();
//                            System.out.println("Available files:");
//                            for (String file : files) {
//                                System.out.println("- " + file);
//                            }
//                            break;
//
//                        case "2":
//                            System.out.print("Enter filename to download: ");
//                            String downloadFile = scanner.nextLine();
//                            boolean success = client.downloadFile(downloadFile);
//                            if (success) {
//                                System.out.println("File downloaded successfully: " + downloadFile);
//                            } else {
//                                System.out.println("Failed to download file");
//                            }
//                            break;
//
//                        case "3":
//                            System.out.print("Enter file path to upload: ");
//                            String uploadFile = scanner.nextLine();
//                            success = client.uploadFile(uploadFile);
//                            if (success) {
//                                System.out.println("File uploaded successfully");
//                            } else {
//                                System.out.println("Failed to upload file");
//                            }
//                            break;
//
//                        case "4":
//                            System.out.println("Exiting...");
//                            client.shutdown();
//                            return;
//
//                        default:
//                            System.out.println("Invalid choice");
//                    }
//                }
//            } else {
//                // Command line mode
//                String command = args[1];
//
//                switch (command) {
//                    case "list":
//                        Set<String> files = client.listAvailableFiles();
//                        System.out.println("Available files:");
//                        for (String file : files) {
//                            System.out.println("- " + file);
//                        }
//                        break;
//
//                    case "download":
//                        if (args.length < 3) {
//                            System.out.println("Missing filename parameter");
//                            break;
//                        }
//                        boolean success = client.downloadFile(args[2]);
//                        if (success) {
//                            System.out.println("File downloaded successfully: " + args[2]);
//                        } else {
//                            System.out.println("Failed to download file");
//                        }
//                        break;
//
//                    case "upload":
//                        if (args.length < 3) {
//                            System.out.println("Missing file path parameter");
//                            break;
//                        }
//                        success = client.uploadFile(args[2]);
//                        if (success) {
//                            System.out.println("File uploaded successfully");
//                        } else {
//                            System.out.println("Failed to upload file");
//                        }
//                        break;
//
//                    default:
//                        System.out.println("Unknown command: " + command);
//                }
//            }
//        } catch (Exception e) {
//            System.err.println("Client error: " + e.getMessage());
//            e.printStackTrace();
//        } finally {
//            if (client != null) {
//                client.shutdown();
//            }
//        }
//    }
//}