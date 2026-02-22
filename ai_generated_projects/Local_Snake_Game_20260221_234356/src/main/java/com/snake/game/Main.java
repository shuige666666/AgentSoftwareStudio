package com.snake.game;

import javax.swing.*;
import java.awt.*;

public class Main {
    public static void main(String[] args) {
        // Check if running in headless environment
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("Cannot run GUI application in headless environment.");
            System.out.println("To run the game, use a system with GUI support.");
            return;
        }
        
        // Set system look and feel to cross-platform
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // Create and show game window
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("贪吃蛇游戏");
            GamePanel gamePanel = new GamePanel();
            
            frame.add(gamePanel);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);
            frame.pack();
            frame.setLocationRelativeTo(null); // Center on screen
            frame.setVisible(true);
            
            // Start the game
            gamePanel.startGame();
        });
    }
}