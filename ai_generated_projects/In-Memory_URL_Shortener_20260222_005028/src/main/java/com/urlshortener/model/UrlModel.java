package com.urlshortener.model;

import java.time.LocalDateTime;

public class UrlModel {
    
    private String originalUrl;
    private String shortCode;
    private LocalDateTime createdAt;
    private int redirectCount;
    
    public UrlModel(String originalUrl, String shortCode) {
        this.originalUrl = originalUrl;
        this.shortCode = shortCode;
        this.createdAt = LocalDateTime.now();
        this.redirectCount = 0;
    }
    
    // Getters and setters
    public String getOriginalUrl() {
        return originalUrl;
    }
    
    public void setOriginalUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }
    
    public String getShortCode() {
        return shortCode;
    }
    
    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public int getRedirectCount() {
        return redirectCount;
    }
    
    public void setRedirectCount(int redirectCount) {
        this.redirectCount = redirectCount;
    }
    
    public void incrementRedirectCount() {
        this.redirectCount++;
    }
}