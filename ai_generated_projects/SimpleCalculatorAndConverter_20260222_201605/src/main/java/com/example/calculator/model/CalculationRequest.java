package com.example.calculator.model;

import javax.validation.constraints.NotNull;

public class CalculationRequest {
    
    @NotNull(message = "第一个数字不能为空")
    private Double firstNumber;
    
    @NotNull(message = "第二个数字不能为空")
    private Double secondNumber;
    
    @NotNull(message = "操作符不能为空")
    private String operation;
    
    public CalculationRequest() {
    }
    
    public CalculationRequest(Double firstNumber, Double secondNumber, String operation) {
        this.firstNumber = firstNumber;
        this.secondNumber = secondNumber;
        this.operation = operation;
    }
    
    public Double getFirstNumber() {
        return firstNumber;
    }
    
    public void setFirstNumber(Double firstNumber) {
        this.firstNumber = firstNumber;
    }
    
    public Double getSecondNumber() {
        return secondNumber;
    }
    
    public void setSecondNumber(Double secondNumber) {
        this.secondNumber = secondNumber;
    }
    
    public String getOperation() {
        return operation;
    }
    
    public void setOperation(String operation) {
        this.operation = operation;
    }
}