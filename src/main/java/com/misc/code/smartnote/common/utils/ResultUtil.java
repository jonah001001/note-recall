package com.misc.code.smartnote.common.utils;

import com.misc.code.smartnote.common.domain.Result;
import com.misc.code.smartnote.common.enums.ResultEnum;

public class ResultUtil {

    public static <T> Result<T> ok(){
        return new Result<>(ResultEnum.SUCCESS);
    }

    public static <T> Result<T> ok(ResultEnum resultEnum, T data){
        return new Result<>(resultEnum.getCode(), resultEnum.getMessage(), data);
    }

    public static <T> Result<T> ok(T data){
        return ok(ResultEnum.SUCCESS, data);
    }

    public static <T> Result<T> fail(String message){
        return new Result<>(ResultEnum.FAIL.getCode(), message);
    }

    public static <T> Result<T> fail(ResultEnum resultEnum){
        return new Result<>(resultEnum);
    }
}
