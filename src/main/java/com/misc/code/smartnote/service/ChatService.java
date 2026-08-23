package com.misc.code.smartnote.service;

import com.misc.code.smartnote.model.ChatMessages;
import com.misc.code.smartnote.model.dto.ChatResp;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

public interface ChatService {

    String create();

    ChatResp talk(String sessionId, String userInput);

    ChatResp agentTalk(String sessionId, String userInput);

    Flux<ServerSentEvent<Object>> talkStream(String sessionId, String userInput);

    List<ChatMessages> history(String sessionId);
}
