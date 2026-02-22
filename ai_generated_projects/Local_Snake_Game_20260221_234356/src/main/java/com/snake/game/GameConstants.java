package com.snake.game;

public class GameConstants {
    
    // 游戏窗口相关常量
    public static final int WINDOW_WIDTH = 600;
    public static final int WINDOW_HEIGHT = 600;
    public static final String WINDOW_TITLE = "贪吃蛇游戏";
    
    // 游戏网格相关常量
    public static final int GRID_SIZE = 20; // 每个格子的像素大小
    public static final int GRID_WIDTH = WINDOW_WIDTH / GRID_SIZE; // 网格宽度（单位：格子）
    public static final int GRID_HEIGHT = WINDOW_HEIGHT / GRID_SIZE; // 网格高度（单位：格子）
    
    // 游戏速度相关常量
    public static final int INITIAL_DELAY = 150; // 初始延迟时间（毫秒），数值越小速度越快
    public static final int MIN_DELAY = 50; // 最小延迟时间（最快速度）
    public static final int SPEED_INCREMENT = 5; // 每次加速减少的延迟时间
    
    // 蛇身相关常量
    public static final int INITIAL_SNAKE_LENGTH = 3; // 初始蛇长度
    
    // 分数相关常量
    public static final int FOOD_SCORE = 10; // 吃到食物获得的分数
    
    // 方向常量
    public static final int UP = 0;
    public static final int DOWN = 1;
    public static final int LEFT = 2;
    public static final int RIGHT = 3;
    
    // 颜色相关常量（RGB值）
    public static final int SNAKE_COLOR_R = 0;
    public static final int SNAKE_COLOR_G = 255;
    public static final int SNAKE_COLOR_B = 0;
    
    public static final int FOOD_COLOR_R = 255;
    public static final int FOOD_COLOR_G = 0;
    public static final int FOOD_COLOR_B = 0;
    
    public static final int BACKGROUND_COLOR_R = 0;
    public static final int BACKGROUND_COLOR_G = 0;
    public static final int BACKGROUND_COLOR_B = 0;
    
    public static final int TEXT_COLOR_R = 255;
    public static final int TEXT_COLOR_G = 255;
    public static final int TEXT_COLOR_B = 255;
}
