////package com.server.usth.controller;
////
////import com.server.usth.impl.DirectoryImpl;
////import org.springframework.beans.factory.annotation.Autowired;
////import org.springframework.beans.factory.annotation.Value;
////import org.springframework.http.ResponseEntity;
////import org.springframework.web.bind.annotation.GetMapping;
////import org.springframework.web.bind.annotation.PathVariable;
////import org.springframework.web.bind.annotation.RequestMapping;
////import org.springframework.web.bind.annotation.RestController;
////
////import java.nio.file.Files;
////import java.nio.file.Path;
////import java.nio.file.Paths;
////
////@RestController
////@RequestMapping
////public class FileDownloadController {
////
////
////    @Value("${download.directory:downloads}")
////    private String downloadDirectory;
////
////    @Autowired
////    private DirectoryImpl directory;
////
////    @GetMapping("/download/{filename}")
////    public ResponseEntity<byte[]> downloadFile(@PathVariable String filename) {
////        try {
////            Path filePath = Paths.get(downloadDirectory, filename);
////            byte[] data = Files.readAllBytes(filePath);
////            return ResponseEntity.ok()
////                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
////                    .body(data);
////        } catch (Exception e) {
////            return ResponseEntity.badRequest().body(null);
////        }
////    }
////}
//
//
//package com.server.usth.controller;
//
//import com.server.usth.impl.DirectoryImpl;
//import com.server.usth.services.DaemonService;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.core.io.ByteArrayResource;
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.List;
//import java.util.Set;
//import java.util.concurrent.*;
//
//@RestController
//@RequestMapping("/api/download")
//@CrossOrigin(origins = "*")
//public class FileDownloadController {
//
//    @Autowired
//    private DirectoryImpl directory;
//
//    private static final int CHUNK_SIZE = 1024 * 1024; // 1MB chunks
//    private static final int MAX_THREADS = 3;
//
//    @GetMapping("/{filename}")
//    public ResponseEntity<?> downloadFile(@PathVariable String filename) {
//        try {
//            List<DaemonService> availableDaemons = directory.getDaemonsForFile(filename);
//
//            if (availableDaemons.isEmpty()) {
//                return ResponseEntity.notFound().build();
//            }
//
//            // Get file size from first available daemon
//            long fileSize = availableDaemons.get(0).getFileSize(filename);
//            int numChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
//
//            // Create thread pool for parallel downloads
//            ExecutorService executorService = Executors.newFixedThreadPool(
//                    Math.min(MAX_THREADS, availableDaemons.size())
//            );
//
//            // Prepare buffer for complete file
//            byte[] completeFile = new byte[(int) fileSize];
//            CountDownLatch latch = new CountDownLatch(numChunks);
//
//            // Distribute chunks among daemons
//            for (int i = 0; i < numChunks; i++) {
//                final int chunkIndex = i;
//                final long offset = (long) i * CHUNK_SIZE;
//                final int currentChunkSize = (int) Math.min(CHUNK_SIZE, fileSize - offset);
//
//                // Round-robin daemon selection
//                final DaemonService daemon = availableDaemons.get(i % availableDaemons.size());
//
//                executorService.submit(() -> {
//                    try {
//                        byte[] chunk = daemon.downloadChunk(filename, offset, currentChunkSize);
//                        synchronized (completeFile) {
//                            System.arraycopy(chunk, 0, completeFile, (int) offset, chunk.length);
//                        }
//                    } catch (Exception e) {
//                        e.printStackTrace();
//                    } finally {
//                        latch.countDown();
//                    }
//                });
//            }
//
//            // Wait for all chunks to be downloaded
//            latch.await(30, TimeUnit.SECONDS);
//            executorService.shutdown();
//
//            // Prepare the response
//            ByteArrayResource resource = new ByteArrayResource(completeFile);
//            HttpHeaders headers = new HttpHeaders();
//            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename);
//
//            return ResponseEntity.ok()
//                    .headers(headers)
//                    .contentLength(fileSize)
//                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
//                    .body(resource);
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            return ResponseEntity.internalServerError()
//                    .body("Error downloading file: " + e.getMessage());
//        }
//    }
//
//    @GetMapping("/files")
//    public ResponseEntity<Set<String>> listFiles() {
//        return ResponseEntity.ok(directory.getAvailableFiles());
//    }
//}