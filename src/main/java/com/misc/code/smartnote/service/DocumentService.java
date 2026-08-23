package com.misc.code.smartnote.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

public interface DocumentService {

    void ingest(MultipartFile file) throws IOException;

    void ingest(String fileName, InputStream is) throws IOException;
}
