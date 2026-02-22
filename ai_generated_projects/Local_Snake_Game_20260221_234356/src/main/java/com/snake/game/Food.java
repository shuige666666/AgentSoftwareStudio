package com.snake.game;

import java.awt.Point;
import java.util.Random;

public class Food {
    private Point position;
    private final int gridSize;
    private final Random random;
    
    public Food(int gridSize) {
        this.gridSize = gridSize;
        this.random = new Random();
        generateNewFood();
    }
    
    public void generateNewFood() {
        int x = random.nextInt(gridSize);
        int y = random.nextInt(gridSize);
        this.position = new Point(x, y);
    }
    
    public Point getPosition() {
        return position;
    }
    
    public boolean isEaten(Point snakeHead) {
        return snakeHead.equals(position);
    }
}