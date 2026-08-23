package com.misc.code.smartnote.common.domain;

import com.misc.code.smartnote.common.enums.ResultEnum;
import lombok.Data;

@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public Result(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public Result(ResultEnum resultEnum){
        this(resultEnum.getCode(), resultEnum.getMessage());
    }



    public Result() {
    }
}
