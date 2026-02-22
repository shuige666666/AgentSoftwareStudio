package com.example.calculator.service;

import org.springframework.stereotype.Service;

@Service
public class ConversionService {
    
    // 长度转换方法
    public double convertLength(double value, String fromUnit, String toUnit) {
        // 将所有单位转换为米作为中间单位
        double meters = 0;
        
        switch (fromUnit.toLowerCase()) {
            case "m":
                meters = value;
                break;
            case "km":
                meters = value * 1000;
                break;
            case "mile":
            case "miles":
                meters = value * 1609.34;
                break;
            default:
                throw new IllegalArgumentException("不支持的长度单位: " + fromUnit);
        }
        
        switch (toUnit.toLowerCase()) {
            case "m":
                return meters;
            case "km":
                return meters / 1000;
            case "mile":
            case "miles":
                return meters / 1609.34;
            default:
                throw new IllegalArgumentException("不支持的长度单位: " + toUnit);
        }
    }
    
    // 温度转换方法
    public double convertTemperature(double value, String fromUnit, String toUnit) {
        double celsius = 0;
        
        switch (fromUnit.toLowerCase()) {
            case "c":
            case "celsius":
                celsius = value;
                break;
            case "f":
            case "fahrenheit":
                celsius = (value - 32) * 5.0 / 9.0;
                break;
            case "k":
            case "kelvin":
                celsius = value - 273.15;
                break;
            default:
                throw new IllegalArgumentException("不支持的温度单位: " + fromUnit);
        }
        
        switch (toUnit.toLowerCase()) {
            case "c":
            case "celsius":
                return celsius;
            case "f":
            case "fahrenheit":
                return celsius * 9.0 / 5.0 + 32;
            case "k":
            case "kelvin":
                return celsius + 273.15;
            default:
                throw new IllegalArgumentException("不支持的温度单位: " + toUnit);
        }
    }
    
    // 重量转换方法
    public double convertWeight(double value, String fromUnit, String toUnit) {
        // 将所有单位转换为克作为中间单位
        double grams = 0;
        
        switch (fromUnit.toLowerCase()) {
            case "g":
            case "gram":
            case "grams":
                grams = value;
                break;
            case "kg":
            case "kilogram":
            case "kilograms":
                grams = value * 1000;
                break;
            case "lb":
            case "lbs":
            case "pound":
            case "pounds":
                grams = value * 453.592;
                break;
            default:
                throw new IllegalArgumentException("不支持的重量单位: " + fromUnit);
        }
        
        switch (toUnit.toLowerCase()) {
            case "g":
            case "gram":
            case "grams":
                return grams;
            case "kg":
            case "kilogram":
            case "kilograms":
                return grams / 1000;
            case "lb":
            case "lbs":
            case "pound":
            case "pounds":
                return grams / 453.592;
            default:
                throw new IllegalArgumentException("不支持的重量单位: " + toUnit);
        }
    }
    
    // 体积转换方法
    public double convertVolume(double value, String fromUnit, String toUnit) {
        // 将所有单位转换为升作为中间单位
        double liters = 0;
        
        switch (fromUnit.toLowerCase()) {
            case "l":
            case "liter":
            case "liters":
                liters = value;
                break;
            case "ml":
            case "milliliter":
            case "milliliters":
                liters = value / 1000;
                break;
            case "gal":
            case "gallon":
            case "gallons":
                liters = value * 3.78541;
                break;
            default:
                throw new IllegalArgumentException("不支持的体积单位: " + fromUnit);
        }
        
        switch (toUnit.toLowerCase()) {
            case "l":
            case "liter":
            case "liters":
                return liters;
            case "ml":
            case "milliliter":
            case "milliliters":
                return liters * 1000;
            case "gal":
            case "gallon":
            case "gallons":
                return liters / 3.78541;
            default:
                throw new IllegalArgumentException("不支持的体积单位: " + toUnit);
        }
    }
}