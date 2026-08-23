package com.misc.code.smartnote.model.req;

import jakarta.validation.constraints.NotBlank;

public record ChatReq(

        @NotBlank(message = "请选择一个对话")
        String sessionId,

        @NotBlank(message = "请输入您的问题")
        String userInput
) {


}
