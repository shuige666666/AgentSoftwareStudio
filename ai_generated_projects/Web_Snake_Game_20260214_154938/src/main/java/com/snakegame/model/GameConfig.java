package com.snakegame.model;

public class GameConfig {
    
    // 游戏网格大小（每个格子的像素）
    private static final int GRID_SIZE = 20;
    
    // 初始游戏速度（毫秒间隔）
    private static final int INITIAL_SPEED = 150;
    
    // 画布宽度
    private static final int CANVAS_WIDTH = 800;
    
    // 画布高度
    private static final int CANVAS_HEIGHT = 600;
    
    /**
     * 获取网格大小
     * @return 网格大小（像素）
     */
    public static int getGridSize() {
        return GRID_SIZE;
    }
    
    /**
     * 获取初始游戏速度
     * @return 初始速度（毫秒）
     */
    public static int getInitialSpeed() {
        return INITIAL_SPEED;
    }
    
    /**
     * 获取画布宽度
     * @return 画布宽度（像素）
     */
    public static int getCanvasWidth() {
        return CANVAS_WIDTH;
    }
    
    /**
     * 获取画布高度
     * @return 画布高度（像素）
     */
    public static int getCanvasHeight() {
        return CANVAS_HEIGHT;
    }
}