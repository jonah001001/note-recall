package com.misc.code.smartnote.controller;

import com.misc.code.smartnote.common.domain.Result;
import com.misc.code.smartnote.common.utils.ResultUtil;
import com.misc.code.smartnote.service.DocumentService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@AllArgsConstructor
@RestController
@RequestMapping("/docs")
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/upload")
    public Result<Void> upload(@RequestPart("file") MultipartFile file) throws IOException {
        documentService.ingest(file);
        return ResultUtil.ok();
    }
}
