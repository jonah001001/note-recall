package com.misc.code.smartnote.controller;

import com.misc.code.smartnote.common.domain.Result;
import com.misc.code.smartnote.common.utils.ResultUtil;
import com.misc.code.smartnote.model.dto.ChatResp;
import com.misc.code.smartnote.model.req.ChatReq;
import com.misc.code.smartnote.service.ChatService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@AllArgsConstructor
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;

    @GetMapping("/new")
    public Result<String> createChat(){
        return ResultUtil.ok(chatService.create());
    }

    @PostMapping("/talk")
    public Result<ChatResp> talk(@Valid @RequestBody ChatReq chatReq) {
        return  ResultUtil.ok(chatService.talk(chatReq.sessionId(), chatReq.userInput()));
    }

    @PostMapping("/agent/talk")
    public Result<ChatResp> agentTalk(@Valid @RequestBody ChatReq chatReq) {
        return  ResultUtil.ok(chatService.agentTalk(chatReq.sessionId(), chatReq.userInput()));
    }

    @PostMapping(value = "/talk/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> talkStream(@Valid @RequestBody ChatReq chatReq) {
        return chatService.talkStream(chatReq.sessionId(), chatReq.userInput());
    }
}
