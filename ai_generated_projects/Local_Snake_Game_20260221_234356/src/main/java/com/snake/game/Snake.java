package com.snake.game;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

public class Snake {
    private List<Point> body;
    private Direction direction;
    private static final int INITIAL_LENGTH = 3;
    
    public enum Direction {
        UP, DOWN, LEFT, RIGHT
    }
    
    public Snake(int startX, int startY) {
        body = new ArrayList<>();
        // 初始化蛇的身体，从头到尾
        for (int i = 0; i < INITIAL_LENGTH; i++) {
            body.add(new Point(startX - i, startY));
        }
        direction = Direction.RIGHT;
    }
    
    public void move() {
        Point head = getHeadPosition();
        Point newHead = new Point(head);
        
        switch (direction) {
            case UP:
                newHead.y -= 1;
                break;
            case DOWN:
                newHead.y += 1;
                break;
            case LEFT:
                newHead.x -= 1;
                break;
            case RIGHT:
                newHead.x += 1;
                break;
        }
        
        // 将新的头部添加到身体的开头
        body.add(0, newHead);
        // 移除尾巴（除非蛇需要增长）
        body.remove(body.size() - 1);
    }
    
    public void grow() {
        // 在当前位置添加一个新的尾部段
        Point tail = new Point(body.get(body.size() - 1));
        body.add(tail);
    }
    
    public void changeDirection(Direction newDirection) {
        // 防止蛇反向移动（例如：向上移动时不能直接向下移动）
        if (isOppositeDirection(newDirection)) {
            return;
        }
        direction = newDirection;
    }
    
    private boolean isOppositeDirection(Direction newDirection) {
        switch (direction) {
            case UP:
                return newDirection == Direction.DOWN;
            case DOWN:
                return newDirection == Direction.UP;
            case LEFT:
                return newDirection == Direction.RIGHT;
            case RIGHT:
                return newDirection == Direction.LEFT;
            default:
                return false;
        }
    }
    
    public List<Point> getBody() {
        return new ArrayList<>(body); // 返回副本以防止外部修改
    }
    
    public Point getHeadPosition() {
        if (body.isEmpty()) {
            return null;
        }
        return new Point(body.get(0)); // 返回副本以防止外部修改
    }
    
    public boolean checkCollision() {
        Point head = getHeadPosition();
        if (head == null) {
            return false;
        }
        
        // 检查是否撞到自己
        for (int i = 1; i < body.size(); i++) {
            if (head.equals(body.get(i))) {
                return true;
            }
        }
        
        return false;
    }
}