package com.misc.code.smartnote.controller;

import com.misc.code.smartnote.common.domain.Result;
import com.misc.code.smartnote.common.utils.ResultUtil;
import lombok.AllArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@AllArgsConstructor
@RestController
@RequestMapping
public class TestController {

    private final VectorStore vectorStore;

    @GetMapping("/debug/search")
    public Result<List<Map<String, Object>>> search(@RequestParam String q,
                                                    @RequestParam(defaultValue = "10") int topK) {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(q).topK(topK).build());
        return ResultUtil.ok(docs.stream()
                .map(d -> Map.<String, Object>of(
                        "score", d.getScore(),
                        "file", d.getMetadata().getOrDefault("filename", ""),
                        "title", d.getMetadata().getOrDefault("title", ""),
                        "preview", d.getText().substring(0, Math.min(120, d.getText().length()))))
                .toList());
    }
}
