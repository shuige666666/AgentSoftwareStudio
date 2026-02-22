package com.snakegame.exception;

public class GameException extends RuntimeException {
    private final String errorCode;

    public GameException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

