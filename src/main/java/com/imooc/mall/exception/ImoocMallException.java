package com.imooc.mall.exception;

public class ImoocMallException extends RuntimeException{

    private final Integer code;
    private final String message;

    public ImoocMallException(String message, Integer code) {
        this.code = code;
        this.message = message;
    }

    public ImoocMallException(ImoocMallExceptionEnum exceptionEnum){
        this(exceptionEnum.getMsg(), exceptionEnum.getCode());
    }

    public Integer getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
