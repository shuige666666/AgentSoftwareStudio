package com.snake.game;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Random;

public class GamePanel extends JPanel implements ActionListener {
    
    private static final int BOARD_WIDTH = 600;
    private static final int BOARD_HEIGHT = 600;
    private static final int UNIT_SIZE = 25;
    private static final int GAME_UNITS = (BOARD_WIDTH * BOARD_HEIGHT) / (UNIT_SIZE * UNIT_SIZE);
    
    private int delay = 75; // Default speed
    private String speedMode = "Normal";
    
    private ArrayList<Point> snake;
    private Point food;
    private char direction = 'R'; // R-Right, L-Left, U-Up, D-Down
    private boolean running = false;
    private boolean isFirstStart = true; // To distinguish between first start and game over
    private Timer timer;
    private int score = 0;
    
    public GamePanel() {
        this.setPreferredSize(new Dimension(BOARD_WIDTH, BOARD_HEIGHT));
        this.setBackground(new Color(20, 20, 20)); // Dark background
        this.setFocusable(true);
        this.addKeyListener(new MyKeyAdapter(this));
        
        // Initialize empty snake for safety
        snake = new ArrayList<>();
        // Do not start game immediately
    }
    
    public void startGame() {
        if (timer != null) {
            timer.stop();
        }
        snake = new ArrayList<>();
        snake.add(new Point(0, 0)); // Head of the snake
        
        generateFood();
        
        direction = 'R';
        score = 0;
        running = true;
        isFirstStart = false;
        
        timer = new Timer(delay, this);
        timer.start();
    }
    
    public void stopGame() {
        running = false;
        if (timer != null) {
            timer.stop();
        }
    }
    
    public void setSpeed(int delay, String mode) {
        this.delay = delay;
        this.speedMode = mode;
        repaint();
    }
    
    public void moveSnake() {
        if (!running) return;
        
        Point newHead = new Point(snake.get(0));
        
        switch(direction) {
            case 'U':
                newHead.y -= UNIT_SIZE;
                break;
            case 'D':
                newHead.y += UNIT_SIZE;
                break;
            case 'L':
                newHead.x -= UNIT_SIZE;
                break;
            case 'R':
                newHead.x += UNIT_SIZE;
                break;
        }
        
        snake.add(0, newHead); // Add new head
        
        // Check if food is eaten
        if (newHead.equals(food)) {
            updateScore();
            generateFood();
        } else {
            snake.remove(snake.size() - 1); // Remove tail if no food eaten
        }
    }
    
    public void generateFood() {
        Random random = new Random();
        int x = random.nextInt(BOARD_WIDTH / UNIT_SIZE) * UNIT_SIZE;
        int y = random.nextInt(BOARD_HEIGHT / UNIT_SIZE) * UNIT_SIZE;
        
        food = new Point(x, y);
        
        // Make sure food doesn't appear on snake
        while (snake.contains(food)) {
            x = random.nextInt(BOARD_WIDTH / UNIT_SIZE) * UNIT_SIZE;
            y = random.nextInt(BOARD_HEIGHT / UNIT_SIZE) * UNIT_SIZE;
            food = new Point(x, y);
        }
    }
    
    public boolean checkCollision() {
        // Check if head collides with body
        Point head = snake.get(0);
        for (int i = 1; i < snake.size(); i++) {
            if (head.equals(snake.get(i))) {
                return true;
            }
        }
        
        // Check if head touches left border
        if (head.x < 0) {
            return true;
        }
        
        // Check if head touches right border
        if (head.x >= BOARD_WIDTH) {
            return true;
        }
        
        // Check if head touches top border
        if (head.y < 0) {
            return true;
        }
        
        // Check if head touches bottom border
        if (head.y >= BOARD_HEIGHT) {
            return true;
        }
        
        return false;
    }
    
    public void updateScore() {
        score++;
    }
    
    public void gameOver(Graphics2D g2d) {
        // Semi-transparent overlay
        g2d.setColor(new Color(0, 0, 0, 180));
        g2d.fillRect(0, 0, BOARD_WIDTH, BOARD_HEIGHT);

        if (isFirstStart) {
            // Title Screen
            g2d.setColor(new Color(46, 204, 113)); // Green
            g2d.setFont(new Font("SansSerif", Font.BOLD, 60));
            FontMetrics metrics = getFontMetrics(g2d.getFont());
            g2d.drawString("Snake Game", (BOARD_WIDTH - metrics.stringWidth("Snake Game")) / 2, BOARD_HEIGHT / 2 - 80);
        } else {
            // Game Over text
            g2d.setColor(new Color(231, 76, 60)); // Red
            g2d.setFont(new Font("SansSerif", Font.BOLD, 50));
            FontMetrics metrics2 = getFontMetrics(g2d.getFont());
            g2d.drawString("Game Over", (BOARD_WIDTH - metrics2.stringWidth("Game Over")) / 2, BOARD_HEIGHT / 2 - 80);
            
            // Score
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("SansSerif", Font.BOLD, 30));
            FontMetrics metrics1 = getFontMetrics(g2d.getFont());
            g2d.drawString("Score: " + score, (BOARD_WIDTH - metrics1.stringWidth("Score: " + score)) / 2, BOARD_HEIGHT / 2 - 20);
        }
        
        // Speed Selection Instructions
        g2d.setColor(new Color(200, 200, 200));
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 18));
        String speedText = "Current Speed: " + speedMode;
        FontMetrics metricsSpeed = getFontMetrics(g2d.getFont());
        g2d.drawString(speedText, (BOARD_WIDTH - metricsSpeed.stringWidth(speedText)) / 2, BOARD_HEIGHT / 2 + 40);
        
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 16));
        String keysText = "Press 1: Slow | 2: Normal | 3: Fast";
        FontMetrics metricsKeys = getFontMetrics(g2d.getFont());
        g2d.drawString(keysText, (BOARD_WIDTH - metricsKeys.stringWidth(keysText)) / 2, BOARD_HEIGHT / 2 + 70);
        
        // Restart instruction
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 20));
        FontMetrics metrics3 = getFontMetrics(g2d.getFont());
        String startText = "Press SPACE to Start";
        g2d.drawString(startText, (BOARD_WIDTH - metrics3.stringWidth(startText)) / 2, BOARD_HEIGHT / 2 + 120);
    }
    
    @Override
    public void paintComponent(Graphics g) {
        super.paintComponent(g);
        draw(g);
    }
    
    public void draw(Graphics g) {
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw Grid
        g2d.setColor(new Color(35, 35, 35));
        for (int i = 0; i < BOARD_WIDTH / UNIT_SIZE; i++) {
            g2d.drawLine(i * UNIT_SIZE, 0, i * UNIT_SIZE, BOARD_HEIGHT);
        }
        for (int i = 0; i < BOARD_HEIGHT / UNIT_SIZE; i++) {
            g2d.drawLine(0, i * UNIT_SIZE, BOARD_WIDTH, i * UNIT_SIZE);
        }

        if (running && snake != null && !snake.isEmpty()) {
            // Draw Food
            if (food != null) {
                g2d.setColor(new Color(231, 76, 60)); // Alizarin Red
                g2d.fillOval(food.x + 2, food.y + 2, UNIT_SIZE - 4, UNIT_SIZE - 4);
                
                // Shine on food
                g2d.setColor(new Color(255, 255, 255, 100));
                g2d.fillOval(food.x + 6, food.y + 6, UNIT_SIZE / 3, UNIT_SIZE / 3);
            }

            // Draw Snake
            for (int i = 0; i < snake.size(); i++) {
                if (i == 0) {
                    g2d.setColor(new Color(46, 204, 113)); // Emerald Green
                } else {
                    g2d.setColor(new Color(39, 174, 96)); // Nephritis Green
                }
                g2d.fillRoundRect(snake.get(i).x + 1, snake.get(i).y + 1, UNIT_SIZE - 2, UNIT_SIZE - 2, 8, 8);
            }
            
            // Draw Score (Top Center)
            g2d.setColor(new Color(255, 255, 255, 200));
            g2d.setFont(new Font("SansSerif", Font.BOLD, 18));
            FontMetrics metrics = g2d.getFontMetrics();
            String scoreText = "Score: " + score;
            g2d.drawString(scoreText, (BOARD_WIDTH - metrics.stringWidth(scoreText)) / 2, 25);
        } else {
            gameOver(g2d);
        }
    }
    
    @Override
    public void actionPerformed(ActionEvent e) {
        if (running) {
            moveSnake();
            if (checkCollision()) {
                running = false;
                timer.stop();
            }
        }
        repaint();
    }
    
    public char getDirection() {
        return direction;
    }
    
    public void setDirection(char direction) {
        this.direction = direction;
    }
    
    public boolean isRunning() {
        return running;
    }
    
    public int getScore() {
        return score;
    }
    
    // Inner class to handle key events
    public class MyKeyAdapter extends KeyAdapter {
        private GamePanel gamePanel;
        
        public MyKeyAdapter(GamePanel gamePanel) {
            this.gamePanel = gamePanel;
        }
        
        @Override
        public void keyPressed(KeyEvent e) {
            switch(e.getKeyCode()) {
                case KeyEvent.VK_LEFT:
                case KeyEvent.VK_A:
                    if (gamePanel.getDirection() != 'R') {
                        gamePanel.setDirection('L');
                    }
                    break;
                case KeyEvent.VK_RIGHT:
                case KeyEvent.VK_D:
                    if (gamePanel.getDirection() != 'L') {
                        gamePanel.setDirection('R');
                    }
                    break;
                case KeyEvent.VK_UP:
                case KeyEvent.VK_W:
                    if (gamePanel.getDirection() != 'D') {
                        gamePanel.setDirection('U');
                    }
                    break;
                case KeyEvent.VK_DOWN:
                case KeyEvent.VK_S:
                    if (gamePanel.getDirection() != 'U') {
                        gamePanel.setDirection('D');
                    }
                    break;
                case KeyEvent.VK_SPACE:
                    if (!gamePanel.isRunning()) {
                        // Restart the game
                        gamePanel.startGame();
                    }
                    break;
                // Speed selection
                case KeyEvent.VK_1:
                    if (!gamePanel.isRunning()) {
                        gamePanel.setSpeed(150, "Slow");
                    }
                    break;
                case KeyEvent.VK_2:
                    if (!gamePanel.isRunning()) {
                        gamePanel.setSpeed(75, "Normal");
                    }
                    break;
                case KeyEvent.VK_3:
                    if (!gamePanel.isRunning()) {
                        gamePanel.setSpeed(40, "Fast");
                    }
                    break;
            }
        }
    }
}
