package com.misc.code.smartnote.advisors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

import java.util.List;
import java.util.Map;

@Slf4j
public class RagMetricsAdvisor implements BaseAdvisor {

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        List<Message> instructions = request.prompt().getInstructions();
        for (Message msg : instructions){
            log.info("发送给大模型的prompt: {}", msg.getText());
        }
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        Map<String, Object> context = response.context();
//        ObjectMapper objectMapper = new ObjectMapper();
//        try {
//            String info = objectMapper.writeValueAsString(context);
//            log.info("本轮上下文：{}", info);
//        } catch (JsonProcessingException e) {
//            log.error("json序列化失败", e);
//        }
        String sessionId = context.get(ChatMemory.CONVERSATION_ID).toString();
        List<Document> docs = (List<Document>) context.get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        log.info("会话id: {}, 文档数量：{}", sessionId, (docs == null || docs.isEmpty()) ? 0 : docs.size());
        String resp = response.chatResponse().getResult().getOutput().getText();
        log.info("大模型回复：{}", resp);
        return response;
    }

    @Override
    public int getOrder() {
        return 3;
    }
}