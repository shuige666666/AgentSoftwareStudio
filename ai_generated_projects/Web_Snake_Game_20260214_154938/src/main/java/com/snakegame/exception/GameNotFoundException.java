package com.snakegame.exception;

public class GameNotFoundException extends GameException {
    private static final String ERROR_CODE = "GAME_NOT_FOUND";

    public GameNotFoundException(String message) {
        super(ERROR_CODE, message);
    }

    public GameNotFoundException() {
        super(ERROR_CODE, "Game session not found");
    }
}