package com.misc.code.smartnote.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface DocumentService {

    void ingest(MultipartFile file) throws IOException;

    void ingest(String fileName, InputStream is) throws IOException;

    void batchIngest(List<MultipartFile> files) throws  IOException;
}
