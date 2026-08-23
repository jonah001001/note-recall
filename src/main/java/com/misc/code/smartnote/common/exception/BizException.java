package com.misc.code.smartnote.common.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class BizException extends RuntimeException{

    private int code;

    private String message;

    private Object data;

    public BizException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }
}
