package com.snakegame.controller;

import com.snakegame.model.GameState;
import com.snakegame.service.GameService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/game")
public class GameController {
    
    @Autowired
    private GameService gameService;
    
    @GetMapping("/play")
    public String getGamePage(Model model) {
        GameState gameState = gameService.getCurrentGameState();
        model.addAttribute("gameState", gameState);
        return "game"; // 返回游戏页面模板名
    }
    
    @PostMapping("/start")
    @ResponseBody
    public ResponseEntity<GameState> startNewGame() {
        GameState newGameState = gameService.startNewGame();
        return ResponseEntity.ok(newGameState);
    }
    
    @GetMapping("/state")
    @ResponseBody
    public ResponseEntity<GameState> getGameState() {
        GameState currentState = gameService.getCurrentGameState();
        return ResponseEntity.ok(currentState);
    }
    
    @PostMapping("/direction")
    @ResponseBody
    public ResponseEntity<Void> updateDirection(@RequestParam String direction) {
        gameService.updateDirection(direction);
        return ResponseEntity.ok().build();
    }
}