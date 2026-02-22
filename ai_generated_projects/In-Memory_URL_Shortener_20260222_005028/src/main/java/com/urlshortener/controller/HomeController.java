package com.urlshortener.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
    
    @GetMapping("/")
    public String home(Model model) {
        // Add any necessary attributes for the home page
        model.addAttribute("title", "URL Shortener");
        model.addAttribute("description", "Paste your long URL to create a short link");
        // Forward to the static index.html in src/main/resources/static so Thymeleaf template resolver is not required
        return "forward:/index.html";
    }
}
