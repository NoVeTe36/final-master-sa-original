package com.server.usth.controller;

import com.server.usth.impl.DirectoryImpl;
import com.server.usth.model.FileFragment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/upload")
public class FileUploadController {
    private static final int REPLICATION_FACTOR = 3;

    @Value("${upload.directory:uploads}")
    private String uploadDirectory;

    @Autowired
    private DirectoryImpl directory;

    @PostMapping
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            String filename = file.getOriginalFilename();
            byte[] fileData = file.getBytes();

            // Get available daemons
            Set<String> availableDaemons = directory.getAvailableDaemons();
            if (availableDaemons.size() < REPLICATION_FACTOR) {
                return ResponseEntity.badRequest()
                        .body("Not enough daemons available. Need at least " + REPLICATION_FACTOR);
            }

            // Calculate fragment size based on number of daemons
            int numFragments = availableDaemons.size();
            int fragmentSize = (int) Math.ceil((double) fileData.length / numFragments);

            List<String> daemonList = new ArrayList<>(availableDaemons);

            // Split file and create replicas
            for (int i = 0; i < numFragments; i++) {
                int start = i * fragmentSize;
                int end = Math.min(start + fragmentSize, fileData.length);
                byte[] fragmentData = Arrays.copyOfRange(fileData, start, end);

                // Create and store original fragment
                String fragmentName = String.format("%s.part%d", filename, i);
                storeFragment(fragmentData, fragmentName, filename, i, daemonList.get(i), 0);

                // Create and store replicas
                for (int r = 1; r < REPLICATION_FACTOR; r++) {
                    String replicaDaemon = daemonList.get((i + r) % daemonList.size());
                    String replicaName = String.format("%s.part%d.replica%d", filename, i, r);
                    storeFragment(fragmentData, replicaName, filename, i, replicaDaemon, r);
                }
            }

            return ResponseEntity.ok("File uploaded and replicated successfully: " + filename);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Failed to upload file: " + e.getMessage());
        }
    }

    private void storeFragment(byte[] data, String fragmentName, String originalFileName,
                               int fragmentIndex, String daemonId, int replicaNumber)
            throws Exception {
        // Create daemon directory if it doesn't exist
        Path daemonPath = Paths.get(uploadDirectory, daemonId);
        Files.createDirectories(daemonPath);

        // Write fragment to daemon's directory
        Path fragmentPath = daemonPath.resolve(fragmentName);
        Files.write(fragmentPath, data);

        // Register fragment with directory service
        FileFragment fragment = new FileFragment(
                originalFileName, fragmentName, fragmentIndex, data.length, daemonId, replicaNumber
        );
        directory.registerFileFragment(fragment);
    }
}