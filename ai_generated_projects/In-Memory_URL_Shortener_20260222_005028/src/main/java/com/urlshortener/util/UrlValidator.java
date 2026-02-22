package com.urlshortener.util;

import java.util.regex.Pattern;

public class UrlValidator {
    
    private static final String URL_REGEX = "^(https?://)?([\\da-z.-]+)\\.([a-z.]{2,6})([/\\w .-]*)*/?$";
    private static final Pattern URL_PATTERN = Pattern.compile(URL_REGEX);
    
    /**
     * Validates if the provided string is a valid URL format.
     * Supports both HTTP and HTTPS protocols, with optional protocol specification.
     * 
     * @param url The URL string to validate
     * @return true if the URL is valid, false otherwise
     */
    public static boolean isValidUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        
        // Check for basic format using regex
        boolean matchesPattern = URL_PATTERN.matcher(url).matches();
        
        if (!matchesPattern) {
            // Try adding http:// prefix if no protocol is specified
            String prefixedUrl = "http://" + url;
            matchesPattern = URL_PATTERN.matcher(prefixedUrl).matches();
        }
        
        return matchesPattern;
    }
}