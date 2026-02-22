package com.snakegame.model;

import java.util.ArrayList;
import java.util.List;

public class GameState {
    private List<Position> snakeBody; // 蛇的身体位置列表
    private Position foodPosition; // 食物位置
    private int score; // 当前分数
    private int highScore; // 最高分
    private boolean gameOver; // 游戏是否结束
    private Direction currentDirection; // 当前移动方向
    
    public GameState() {
        this.snakeBody = new ArrayList<>();
        // 初始化蛇的位置（从头部开始）
        this.snakeBody.add(new Position(5, 5)); // 初始头部位置
        this.snakeBody.add(new Position(5, 4)); // 初始身体位置
        this.snakeBody.add(new Position(5, 3)); // 初始尾部位置
        this.foodPosition = generateRandomFoodPosition();
        this.score = 0;
        this.highScore = 0;
        this.gameOver = false;
        this.currentDirection = Direction.RIGHT; // 默认向右移动
    }
    
    public boolean isGameOver() {
        return gameOver;
    }
    
    public void setGameOver(boolean gameOver) {
        this.gameOver = gameOver;
    }
    
    public int getScore() {
        return score;
    }
    
    public void setScore(int score) {
        this.score = score;
        if (score > highScore) {
            this.highScore = score;
        }
    }
    
    public int getHighScore() {
        return highScore;
    }
    
    public void setHighScore(int highScore) {
        this.highScore = highScore;
    }
    
    public List<Position> getSnakeBody() {
        return snakeBody;
    }
    
    public void setSnakeBody(List<Position> snakeBody) {
        this.snakeBody = snakeBody;
    }
    
    public Position getFoodPosition() {
        return foodPosition;
    }
    
    public void setFoodPosition(Position foodPosition) {
        this.foodPosition = foodPosition;
    }
    
    public Direction getCurrentDirection() {
        return currentDirection;
    }
    
    public void setCurrentDirection(Direction currentDirection) {
        this.currentDirection = currentDirection;
    }
    
    private Position generateRandomFoodPosition() {
        // 简单生成随机食物位置，实际游戏中可能需要确保不与蛇身重叠
        int x = (int) (Math.random() * 20); // 假设游戏区域为20x20
        int y = (int) (Math.random() * 20);
        return new Position(x, y);
    }
}