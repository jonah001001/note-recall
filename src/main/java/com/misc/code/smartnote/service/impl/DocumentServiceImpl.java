package com.misc.code.smartnote.service.impl;

import com.misc.code.smartnote.service.DocumentService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@AllArgsConstructor
@Slf4j
@Service
public class DocumentServiceImpl implements DocumentService {

    private final VectorStore vectorStore;

    private static final TokenTextSplitter SPLITTER = TokenTextSplitter.builder()
            .withChunkSize(300)
            .withMinChunkSizeChars(100)
            .withMinChunkLengthToEmbed(5)
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .withPunctuationMarks(List.of('。', '？', '！', '.', '?', '!', '\n', '；', ';'))
            .build();

    @Override
    public void ingest(MultipartFile file) throws IOException {
        ingest(file.getOriginalFilename(), file.getInputStream());
    }

    @Override
    public void batchIngest(List<MultipartFile> files) throws IOException {
        for (MultipartFile file : files){
            ingest(file);
        }
    }

    @Override
    public void ingest(String fileName, InputStream is) {
        InputStreamResource resource = new InputStreamResource(is);
        MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(true)
                .withIncludeCodeBlock(true)
                .withAdditionalMetadata("filename", fileName)
                .build();
        List<Document> documents = new MarkdownDocumentReader(resource, config).get();
        List<Document> chunks = SPLITTER.split(documents);
        log.info("切片数量: {}", chunks.size());
        String docName = fileName.replace(".md", "");
        List<Document> enriched = chunks.stream()
                .map(c -> {
                    Object title = c.getMetadata().get("title");
                    String header = title != null ?
                            "【"+docName+" - "+title+"】" : "【"+docName+"】";
                    return new Document(header + c.getText(), c.getMetadata());
                })
                .toList();
        vectorStore.add(enriched);
    }
}
