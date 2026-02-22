package com.example.calculator.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class CalculationController {
    
    @GetMapping("/calculator")
    public String showCalculator(Model model) {
        model.addAttribute("result", "");
        model.addAttribute("error", "");
        return "calculator";
    }
    
    @PostMapping("/calculate")
    public String calculate(
            @RequestParam("operation") String operation,
            @RequestParam("num1") Double num1,
            @RequestParam("num2") Double num2,
            Model model) {
        
        double result = 0;
        String error = "";
        
        try {
            switch (operation) {
                case "add":
                    result = num1 + num2;
                    break;
                case "subtract":
                    result = num1 - num2;
                    break;
                case "multiply":
                    result = num1 * num2;
                    break;
                case "divide":
                    if (num2 == 0) {
                        error = "除数不能为零";
                        break;
                    }
                    result = num1 / num2;
                    break;
                case "power":
                    result = Math.pow(num1, num2);
                    break;
                default:
                    error = "无效的操作";
                    break;
            }
            
            if (error.isEmpty()) {
                model.addAttribute("result", result);
                model.addAttribute("error", "");
            } else {
                model.addAttribute("result", "");
                model.addAttribute("error", error);
            }
        } catch (Exception e) {
            model.addAttribute("result", "");
            model.addAttribute("error", "计算出错: " + e.getMessage());
        }
        
        return "calculator";
    }
}