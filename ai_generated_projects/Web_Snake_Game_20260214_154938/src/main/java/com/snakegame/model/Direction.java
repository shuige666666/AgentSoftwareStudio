package com.snakegame.model;

public enum Direction {
    UP,
    DOWN,
    LEFT,
    RIGHT;

    public Direction opposite() {
        switch (this) {
            case UP:
                return DOWN;
            case DOWN:
                return UP;
            case LEFT:
                return RIGHT;
            case RIGHT:
                return LEFT;
            default:
                throw new IllegalStateException("Unexpected value: " + this);
        }
    }

    public Direction nextClockwise() {
        switch (this) {
            case UP:
                return RIGHT;
            case RIGHT:
                return DOWN;
            case DOWN:
                return LEFT;
            case LEFT:
                return UP;
            default:
                throw new IllegalStateException("Unexpected value: " + this);
        }
    }

    public Direction nextCounterClockwise() {
        switch (this) {
            case UP:
                return LEFT;
            case LEFT:
                return DOWN;
            case DOWN:
                return RIGHT;
            case RIGHT:
                return UP;
            default:
                throw new IllegalStateException("Unexpected value: " + this);
        }
    }
}