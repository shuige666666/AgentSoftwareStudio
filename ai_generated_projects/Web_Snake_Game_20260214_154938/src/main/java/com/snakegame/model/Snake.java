package com.snakegame.model;

import java.util.ArrayList;
import java.util.List;

public class Snake {
    private List<Position> body;
    private Direction direction;
    
    public Snake(int startX, int startY) {
        this.body = new ArrayList<>();
        // 初始蛇身长度为3个单位
        this.body.add(new Position(startX, startY));
        this.body.add(new Position(startX - 1, startY));
        this.body.add(new Position(startX - 2, startY));
        this.direction = Direction.RIGHT; // 默认向右移动
    }
    
    public void move() {
        Position head = getHeadPosition();
        Position newHead;
        
        switch (direction) {
            case UP:
                newHead = new Position(head.getX(), head.getY() - 1);
                break;
            case DOWN:
                newHead = new Position(head.getX(), head.getY() + 1);
                break;
            case LEFT:
                newHead = new Position(head.getX() - 1, head.getY());
                break;
            case RIGHT:
                newHead = new Position(head.getX() + 1, head.getY());
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + direction);
        }
        
        // 将新的头部添加到身体列表的开头
        body.add(0, newHead);
        // 移除尾部（不生长的情况下）
        body.remove(body.size() - 1);
    }
    
    public void grow() {
        // 在当前位置保持尾部不变，下一次移动时不会移除尾部，实现增长
        Position tail = body.get(body.size() - 1);
        body.add(tail); // 添加一个与当前尾部相同位置的新段
    }
    
    public List<Position> getBody() {
        return new ArrayList<>(body); // 返回副本以防止外部修改
    }
    
    public Position getHeadPosition() {
        if (body.isEmpty()) {
            throw new IllegalStateException("com.snakegame.model.Snake has no body");
        }
        return body.get(0);
    }
    
    public void setDirection(Direction newDirection) {
        // 防止蛇反向移动（例如：向上移动时不能直接向下移动）
        if (newDirection == null) return;
        if (newDirection == direction.opposite()) return;
        this.direction = newDirection;
    }
    
    public Direction getDirection() {
        return direction;
    }
}