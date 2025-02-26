package com.server.usth.controller;


import com.server.usth.impl.DirectoryImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/directory")
public class DirectoryController {

    @Autowired
    private DirectoryImpl directoryImpl;

    @GetMapping("/status")
    public String getStatus() {
        return "Directory Service is running";
    }

    @GetMapping("/files")
    public Set<String> getAvailableFiles() {
        return directoryImpl.getAvailableFiles();
    }
}
