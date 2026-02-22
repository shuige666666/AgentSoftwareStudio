package com.urlshortener.service;

import com.urlshortener.model.UrlModel;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class UrlService {
    
    private final Map<String, UrlModel> urlMap = new ConcurrentHashMap<>();
    private final AtomicLong totalLinksCreated = new AtomicLong(0);
    private final AtomicLong redirectCount = new AtomicLong(0);
    
    // Characters used for generating short codes
    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CODE_LENGTH = 6;
    
    /**
     * Generates a unique short code for URLs.
     * 
     * @return A randomly generated short code
     */
    public String generateShortCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            int index = (int) (Math.random() * CHARACTERS.length());
            sb.append(CHARACTERS.charAt(index));
        }
        String shortCode = sb.toString();
        
        // Ensure uniqueness by checking against existing codes
        while (urlMap.containsKey(shortCode)) {
            sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                int index = (int) (Math.random() * CHARACTERS.length());
                sb.append(CHARACTERS.charAt(index));
            }
            shortCode = sb.toString();
        }
        
        return shortCode;
    }
    
    /**
     * Saves a URL mapping with the given short code.
     * 
     * @param shortCode The short code for the URL
     * @param originalUrl The original URL to be shortened
     */
    public void saveUrl(String shortCode, String originalUrl) {
        UrlModel urlModel = new UrlModel(originalUrl, shortCode);
        urlMap.put(shortCode, urlModel);
        totalLinksCreated.incrementAndGet();
    }
    
    /**
     * Retrieves the original URL associated with the given short code.
     * 
     * @param shortCode The short code to look up
     * @return The original URL if found, null otherwise
     */
    public String getOriginalUrl(String shortCode) {
        UrlModel urlModel = urlMap.get(shortCode);
        if (urlModel != null) {
            urlModel.incrementRedirectCount();
            redirectCount.incrementAndGet();
            return urlModel.getOriginalUrl();
        }
        return null;
    }
    
    /**
     * Gets the total number of links created.
     * 
     * @return An AtomicLong representing the total links created
     */
    public AtomicLong getTotalLinksCreated() {
        return totalLinksCreated;
    }
    
    /**
     * Gets the total number of redirects performed.
     * 
     * @return An AtomicLong representing the total redirects
     */
    public AtomicLong getRedirectCount() {
        return redirectCount;
    }
    
    /**
     * Increments the redirect count manually.
     */
    public void incrementRedirectCount() {
        redirectCount.incrementAndGet();
    }
    
    /**
     * Gets the URL map for testing or administrative purposes.
     * 
     * @return The map of short codes to URL models
     */
    public Map<String, UrlModel> getUrlMap() {
        return urlMap;
    }
}