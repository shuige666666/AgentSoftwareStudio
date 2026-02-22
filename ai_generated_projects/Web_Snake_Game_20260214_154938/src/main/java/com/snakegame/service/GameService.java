package com.snakegame.service;

import com.snakegame.exception.GameException;
import com.snakegame.model.Direction;
import com.snakegame.model.GameState;
import com.snakegame.model.Position;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Service
public class GameService {
    
    private static final int GRID_WIDTH = 20;
    private static final int GRID_HEIGHT = 20;
    private static final int INITIAL_SNAKE_LENGTH = 3;
    
    private GameStatus gameStatus;
    private List<Position> snake;
    private Position food;
    private Direction currentDirection;
    private int score;
    private int highScore;
    private Random random;
    
    public GameService() {
        this.random = new Random();
        initializeGame();
    }
    
    public void initializeGame() {
        // 初始化蛇的位置（居中）
        snake = new ArrayList<>();
        int startX = GRID_WIDTH / 2;
        int startY = GRID_HEIGHT / 2;
        
        // 创建初始蛇身（长度为3）
        for (int i = 0; i < INITIAL_SNAKE_LENGTH; i++) {
            snake.add(new Position(startX - i, startY));
        }
        
        // 设置初始方向为右
        currentDirection = Direction.RIGHT;
        
        // 生成第一个食物
        generateFood();
        
        // 重置分数
        score = 0;
        
        // 设置游戏状态为运行中
        gameStatus = GameStatus.RUNNING;
    }
    
    public boolean moveSnake() {
        if (gameStatus != GameStatus.RUNNING) {
            return false;
        }
        
        // 获取蛇头当前位置
        Position head = snake.get(0);
        Position newHead;
        
        // 根据当前方向计算新的头部位置
        switch (currentDirection) {
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
                return false;
        }
        
        // 检查碰撞
        if (checkCollision(newHead)) {
            gameStatus = GameStatus.GAME_OVER;
            updateHighScore();
            return false;
        }
        
        // 将新的头部添加到蛇身
        snake.add(0, newHead);
        
        // 检查是否吃到食物
        if (newHead.equals(food)) {
            // 吃到食物，增加分数
            updateScore(10);
            // 生成新的食物
            generateFood();
            // 不移除尾巴，蛇变长
        } else {
            // 没吃到食物，移除尾巴
            snake.remove(snake.size() - 1);
        }
        
        return true;
    }
    
    public boolean checkCollision(Position newPosition) {
        // 检查是否撞墙
        if (newPosition.getX() < 0 || newPosition.getX() >= GRID_WIDTH || 
            newPosition.getY() < 0 || newPosition.getY() >= GRID_HEIGHT) {
            return true;
        }
        
        // 检查是否撞到自己
        for (int i = 0; i < snake.size(); i++) {
            if (snake.get(i).equals(newPosition)) {
                // 如果是头部与身体碰撞，则游戏结束
                if (i > 0) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    public void generateFood() {
        Position newFood = null;
        boolean validPosition = false;
        
        // 确保食物不会出现在蛇身上
        while (!validPosition) {
            newFood = new Position(
                random.nextInt(GRID_WIDTH),
                random.nextInt(GRID_HEIGHT)
            );
            
            validPosition = true;
            for (Position pos : snake) {
                if (pos.equals(newFood)) {
                    validPosition = false;
                    break;
                }
            }
        }
        
        food = newFood;
    }
    
    public void updateScore(int points) {
        score += points;
        if (score > highScore) {
            highScore = score;
        }
    }
    
    public void changeDirection(Direction newDirection) {
        // 防止蛇反向移动（例如向上移动时不能直接向下）
        if ((currentDirection == Direction.UP && newDirection == Direction.DOWN) ||
            (currentDirection == Direction.DOWN && newDirection == Direction.UP) ||
            (currentDirection == Direction.LEFT && newDirection == Direction.RIGHT) ||
            (currentDirection == Direction.RIGHT && newDirection == Direction.LEFT)) {
            return;
        }
        
        currentDirection = newDirection;
    }
    
    private void updateHighScore() {
        if (score > highScore) {
            highScore = score;
        }
    }

    // Controller-facing API: return a serializable GameState object
    public GameState getCurrentGameState() {
        GameState state = new GameState();
        state.setSnakeBody(new ArrayList<>(this.snake));
        state.setFoodPosition(this.food);
        state.setScore(this.score);
        state.setHighScore(this.highScore);
        state.setGameOver(this.gameStatus == GameStatus.GAME_OVER);
        state.setCurrentDirection(this.currentDirection);
        return state;
    }

    public GameState startNewGame() {
        initializeGame();
        return getCurrentGameState();
    }

    public void updateDirection(String directionStr) {
        if (directionStr == null) return;
        try {
            Direction d = Direction.valueOf(directionStr.toUpperCase());
            changeDirection(d);
        } catch (IllegalArgumentException ex) {
            throw new GameException("INVALID_DIRECTION", "Invalid direction: " + directionStr);
        }
    }
    
    // Getters and Setters for internal fields (if needed)
    public GameStatus getGameStatus() {
        return gameStatus;
    }
    
    public List<Position> getSnake() {
        return snake;
    }
    
    public Position getFood() {
        return food;
    }
    
    public int getScore() {
        return score;
    }
    
    public int getHighScore() {
        return highScore;
    }
    
    public Direction getCurrentDirection() {
        return currentDirection;
    }
    
    public static int getGridWidth() {
        return GRID_WIDTH;
    }
    
    public static int getGridHeight() {
        return GRID_HEIGHT;
    }

    public enum GameStatus {
        RUNNING,
        GAME_OVER
    }
}

