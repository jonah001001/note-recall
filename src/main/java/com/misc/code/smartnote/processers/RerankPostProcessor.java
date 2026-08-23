package com.misc.code.smartnote.processers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;

@Component
@Slf4j
public class RerankPostProcessor implements DocumentPostProcessor {

    private final RestClient restClient;
    private final String model;
    private final int topN;

    public RerankPostProcessor(
            @Value("${rerank.base-url}") String baseUrl,
            @Value("${rerank.model}") String model,
            @Value("${rerank.top-n}") int topN) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
        this.model = model;
        this.topN = topN;
    }

    record RerankRequest(String query, List<String> texts) {}
    record RerankResult(int index, double score) {}

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()){
            return List.of();
        }
        try {
            RerankResult[] results = restClient.post().body(new RerankRequest(query.text(),
                            documents.stream().map(Document::getText).toList()))
                    .retrieve().body(RerankResult[].class);
            List<Document> reranks = Arrays.stream(results).peek(r ->
                            log.info("rerank: score={}, 文件={}", String.format("%.4f", r.score()),
                                    documents.get(r.index()).getMetadata().get("filename")))
                    .filter(r -> r.score() > 0.1)
                    .map(r -> documents.get(r.index()))
                    .limit(topN)
                    .toList();
            log.info("rerank：{}块精排后还剩余{}块", documents.size(), reranks.size());
            return reranks;
        }catch (Exception e){
            log.error("rerank失败，返回粗排结果", e);
            return documents.stream().limit(topN).toList();
        }
    }
}
