package com.misc.code.smartnote.common.enums;

import lombok.Getter;

@Getter
public enum ResultEnum {

    SUCCESS(200, "操作成功"),
    FAIL(500, "操作失败")
    ;

    private final int code;
    private final String message;

    ResultEnum(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
