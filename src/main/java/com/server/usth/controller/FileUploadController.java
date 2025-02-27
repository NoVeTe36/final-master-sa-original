package com.server.usth.controller;

import com.server.usth.services.DaemonService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Autowired;
import com.server.usth.impl.DirectoryImpl;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/upload")
public class FileUploadController {

    @Value("${upload.directory:uploads}")
    private String uploadDirectory;

    @Autowired
    private DirectoryImpl directory;

    @PostMapping
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            File dir = new File(uploadDirectory);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            // Save the file
            String filename = file.getOriginalFilename();
            Path path = Paths.get(uploadDirectory, filename);
            Files.write(path, file.getBytes());

            // Register file with directory service
            directory.registerFile(filename, "local");

            // Debugging: Print registered daemons
            List<DaemonService> daemons = directory.getDaemonsForFile(filename);
            System.out.println("Daemons assigned to " + filename + ": " + daemons.size());

            if (daemons.isEmpty()) {
                return ResponseEntity.badRequest().body("No active daemons to distribute the file.");
            }

            for (DaemonService daemon : daemons) {
                daemon.receiveFile(filename, Files.readAllBytes(path));
                System.out.println("Sent file to daemon: " + daemon.getDaemonId());
            }

            return ResponseEntity.ok("File uploaded and distributed successfully: " + filename);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to upload file: " + e.getMessage());
        }
    }
}