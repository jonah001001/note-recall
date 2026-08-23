package com.misc.code.smartnote.service.impl;

import com.misc.code.smartnote.model.ChatMessages;
import com.misc.code.smartnote.model.dto.ChatResp;
import com.misc.code.smartnote.model.dto.Citation;
import com.misc.code.smartnote.service.ChatService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatClient agentClient;

    public ChatServiceImpl(ChatClient chatClient,
                           ChatMemory chatMemory,
                           @Qualifier("agentClient") ChatClient agentClient) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.agentClient = agentClient;
    }

    @Override
    public String create() {
        log.info("创建新对话");
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public ChatResp talk(String sessionId, String userInput) {
        return getChatResp(sessionId, userInput, chatClient);
    }

    @NonNull
    private static List<Citation> toCitations(List<Document> docs) {
        return docs == null ? List.of() : docs.stream().map(d -> new Citation(
                String.valueOf(d.getMetadata().getOrDefault("filename", "未知来源")),
                        String.valueOf(d.getMetadata().getOrDefault("title", ""))
        )).distinct().toList();
    }

    @Override
    public ChatResp agentTalk(String sessionId, String userInput) {
        return getChatResp(sessionId, userInput, agentClient);
    }

    @NonNull
    private ChatResp getChatResp(String sessionId, String userInput, ChatClient agentClient) {
        ChatClientResponse chatClientResponse = agentClient.prompt().user(userInput)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call().chatClientResponse();
        String content = chatClientResponse.chatResponse().getResult().getOutput().getText();
        List<Document> docs = (List<Document>) chatClientResponse.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        List<Citation> citations = toCitations(docs);
        return new ChatResp(content, citations);
    }

    @Override
    public Flux<ServerSentEvent<Object>> talkStream(String sessionId, String userInput) {
        AtomicBoolean sourcesSent = new AtomicBoolean(false);

        return chatClient.prompt()
                .user(userInput)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .stream()
                .chatClientResponse()
                .flatMap(cr -> {
                   List<ServerSentEvent<Object>> events = new ArrayList<>(2);
                    if (!sourcesSent.get()){
                        List<Document> docs = (List<Document>) cr.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
                        if (docs != null && sourcesSent.compareAndSet(false, true)){
                            events.add(ServerSentEvent.builder((Object) toCitations(docs)).event("sources").build());
                        }
                    }

                    ChatResponse chatResponse = cr.chatResponse();
                    String token = chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null
                            ? chatResponse.getResult().getOutput().getText() : null;
                    if (token != null && !token.isEmpty()){
                        events.add(ServerSentEvent.builder((Object) token).event("message").build());
                    }
                    return Flux.fromIterable(events);
                })
                .concatWith(Flux.just(ServerSentEvent.builder((Object) "").event("done").build()))
                .onErrorResume(e -> {
                    log.error("流式对话异常", e);
                    return Flux.just(ServerSentEvent.builder((Object) "AI服务暂不可用").event("error").build());
                });
    }

    @Override
    public List<ChatMessages> history(String sessionId) {
        return chatMemory.get(sessionId).stream()
                .map(m -> new ChatMessages(m.getMessageType().getValue(), m.getText()))
                .toList();
    }
}
