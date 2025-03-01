package com.server.usth.model;

import jakarta.persistence.*;

import java.io.Serial;
import java.util.List;
import java.io.Serializable;

@Entity
public class File implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    private String fileName;
    private String fileSize;
    private String fileType;
    private String fileDate;
    private int fileIndex;

    @ManyToMany
    private List<Client> fileOwner;

    private String folderName;

    public File() {
    }

    public File(String fileName, String fileSize, String fileType, String fileDate, List<Client> fileOwner) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.fileType = fileType;
        this.fileDate = fileDate;
        this.fileOwner = fileOwner;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileSize() {
        return fileSize;
    }

    public void setFileSize(String fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public String getFileDate() {
        return fileDate;
    }

    public void setFileDate(String fileDate) {
        this.fileDate = fileDate;
    }

    public List<Client> getFileOwner() {
        return fileOwner;
    }

    public void setFileOwner(List<Client> fileOwner) {
        this.fileOwner = fileOwner;
    }

    public void addFileOwner(Client client) {
        this.fileOwner.add(client);
    }

    public void removeFileOwner(Client client) {
        this.fileOwner.remove(client);
    }

    public void clearFileOwner() {
        this.fileOwner.clear();
    }

    public boolean isFileOwner(Client client) {
        return this.fileOwner.contains(client);
    }

    public String getFolderName() {
        return folderName;
    }

    public void setFolderName(String folderName) {
        this.folderName = folderName;
    }

    public int getFileIndex() {
        return fileIndex;
    }

    public void setFileIndex(int fileIndex) {
        this.fileIndex = fileIndex;
    }

}
