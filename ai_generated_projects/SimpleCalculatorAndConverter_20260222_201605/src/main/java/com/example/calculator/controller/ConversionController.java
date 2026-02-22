package com.example.calculator.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ConversionController {
    
    @GetMapping("/converter")
    public String showConverter(Model model) {
        model.addAttribute("result", "");
        model.addAttribute("inputValue", 0.0);
        model.addAttribute("fromUnit", "meter");
        model.addAttribute("toUnit", "kilometer");
        model.addAttribute("conversionType", "length");
        return "converter";
    }
    
    @PostMapping("/convert")
    public String convert(
            @RequestParam Double inputValue,
            @RequestParam String fromUnit,
            @RequestParam String toUnit,
            @RequestParam String conversionType,
            Model model) {
        
        double result = 0.0;
        String errorMessage = null;
        
        try {
            switch (conversionType.toLowerCase()) {
                case "length":
                    result = convertLength(inputValue, fromUnit, toUnit);
                    break;
                case "temperature":
                    result = convertTemperature(inputValue, fromUnit, toUnit);
                    break;
                case "weight":
                    result = convertWeight(inputValue, fromUnit, toUnit);
                    break;
                case "volume":
                    result = convertVolume(inputValue, fromUnit, toUnit);
                    break;
                default:
                    errorMessage = "不支持的转换类型: " + conversionType;
            }
        } catch (IllegalArgumentException e) {
            errorMessage = e.getMessage();
        }
        
        if (errorMessage != null) {
            model.addAttribute("error", errorMessage);
            result = 0.0;
        } else {
            model.addAttribute("error", null);
        }
        
        model.addAttribute("result", result);
        model.addAttribute("inputValue", inputValue);
        model.addAttribute("fromUnit", fromUnit);
        model.addAttribute("toUnit", toUnit);
        model.addAttribute("conversionType", conversionType);
        
        return "converter";
    }
    
    private double convertLength(double value, String fromUnit, String toUnit) {
        // Convert to meters first
        double meters;
        switch (fromUnit.toLowerCase()) {
            case "meter":
                meters = value;
                break;
            case "kilometer":
                meters = value * 1000;
                break;
            case "mile":
                meters = value * 1609.34;
                break;
            default:
                throw new IllegalArgumentException("不支持的长度单位: " + fromUnit);
        }
        
        // Convert from meters to target unit
        switch (toUnit.toLowerCase()) {
            case "meter":
                return meters;
            case "kilometer":
                return meters / 1000;
            case "mile":
                return meters / 1609.34;
            default:
                throw new IllegalArgumentException("不支持的长度单位: " + toUnit);
        }
    }
    
    private double convertTemperature(double value, String fromUnit, String toUnit) {
        double celsius;
        
        switch (fromUnit.toLowerCase()) {
            case "celsius":
                celsius = value;
                break;
            case "fahrenheit":
                celsius = (value - 32) * 5/9;
                break;
            default:
                throw new IllegalArgumentException("不支持的温度单位: " + fromUnit);
        }
        
        switch (toUnit.toLowerCase()) {
            case "celsius":
                return celsius;
            case "fahrenheit":
                return (celsius * 9/5) + 32;
            default:
                throw new IllegalArgumentException("不支持的温度单位: " + toUnit);
        }
    }
    
    private double convertWeight(double value, String fromUnit, String toUnit) {
        // Convert to grams first
        double grams;
        switch (fromUnit.toLowerCase()) {
            case "gram":
                grams = value;
                break;
            case "kilogram":
                grams = value * 1000;
                break;
            case "pound":
                grams = value * 453.592;
                break;
            default:
                throw new IllegalArgumentException("不支持的重量单位: " + fromUnit);
        }
        
        // Convert from grams to target unit
        switch (toUnit.toLowerCase()) {
            case "gram":
                return grams;
            case "kilogram":
                return grams / 1000;
            case "pound":
                return grams / 453.592;
            default:
                throw new IllegalArgumentException("不支持的重量单位: " + toUnit);
        }
    }
    
    private double convertVolume(double value, String fromUnit, String toUnit) {
        // Convert to liters first
        double liters;
        switch (fromUnit.toLowerCase()) {
            case "liter":
                liters = value;
                break;
            case "milliliter":
                liters = value / 1000;
                break;
            case "gallon":
                liters = value * 3.78541;
                break;
            default:
                throw new IllegalArgumentException("不支持的体积单位: " + fromUnit);
        }
        
        // Convert from liters to target unit
        switch (toUnit.toLowerCase()) {
            case "liter":
                return liters;
            case "milliliter":
                return liters * 1000;
            case "gallon":
                return liters / 3.78541;
            default:
                throw new IllegalArgumentException("不支持的体积单位: " + toUnit);
        }
    }
}
