package com.misc.code.smartnote.common.exception;

import com.misc.code.smartnote.common.domain.Result;
import com.misc.code.smartnote.common.utils.ResultUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<String> handle(BizException e){
        return ResultUtil.fail(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<String> handle(MethodArgumentNotValidException e){
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResultUtil.fail(msg);
    }

    @ExceptionHandler(Exception.class)
    public Result<String> handle(Exception e){
        log.error("未捕获异常", e);
        return ResultUtil.fail("系统异常，请稍后再试！");
    }
}
