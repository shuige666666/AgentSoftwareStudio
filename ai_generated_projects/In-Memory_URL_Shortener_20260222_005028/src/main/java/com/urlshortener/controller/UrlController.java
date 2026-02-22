package com.urlshortener.controller;

import com.urlshortener.service.UrlService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class UrlController {
    
    @Autowired
    private UrlService urlService;
    
    @PostMapping("/api/shorten")
    @ResponseBody
    public ResponseEntity<?> shortenUrl(@RequestBody UrlRequest request) {
        String longUrl = request.getUrl();
        
        if (!isValidUrl(longUrl)) {
            return ResponseEntity.badRequest().body(new ApiResponse(false, "Invalid URL format"));
        }
        
        String shortCode = urlService.generateShortCode();
        urlService.saveUrl(shortCode, longUrl);
        
        return ResponseEntity.ok(new ApiResponse(true, "Success", shortCode, 
                urlService.getTotalLinksCreated().get(), 
                urlService.getRedirectCount().get()));
    }
    
    @GetMapping("/{shortCode:[a-zA-Z0-9]{6}}")
    public String redirectUrl(@PathVariable String shortCode, Model model) {
        String longUrl = urlService.getOriginalUrl(shortCode);
        
        if (longUrl == null) {
            // Forward to a static error page instead of returning a view name that may not be resolved
            model.addAttribute("error", "URL not found");
            return "forward:/error.html";
        }
        
        return "redirect:" + longUrl;
    }
    
    @GetMapping("/api/stats")
    @ResponseBody
    public ResponseEntity<ApiStats> getUrlStats() {
        ApiStats stats = new ApiStats();
        stats.setTotalLinks(urlService.getTotalLinksCreated().get());
        stats.setTotalRedirects(urlService.getRedirectCount().get());
        
        return ResponseEntity.ok(stats);
    }
    
    private boolean isValidUrl(String url) {
        // Basic validation for URL format
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }
    
    // Inner classes for API responses
    public static class UrlRequest {
        private String url;
        
        public String getUrl() {
            return url;
        }
        
        public void setUrl(String url) {
            this.url = url;
        }
    }
    
    public static class ApiResponse {
        private boolean success;
        private String message;
        private String shortCode;
        private long totalLinks;
        private long totalRedirects;
        
        public ApiResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
        
        public ApiResponse(boolean success, String message, String shortCode, long totalLinks, long totalRedirects) {
            this.success = success;
            this.message = message;
            this.shortCode = shortCode;
            this.totalLinks = totalLinks;
            this.totalRedirects = totalRedirects;
        }
        
        // Getters and setters
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getShortCode() { return shortCode; }
        public void setShortCode(String shortCode) { this.shortCode = shortCode; }
        public long getTotalLinks() { return totalLinks; }
        public void setTotalLinks(long totalLinks) { this.totalLinks = totalLinks; }
        public long getTotalRedirects() { return totalRedirects; }
        public void setTotalRedirects(long totalRedirects) { this.totalRedirects = totalRedirects; }
    }
    
    public static class ApiStats {
        private long totalLinks;
        private long totalRedirects;
        
        public long getTotalLinks() { return totalLinks; }
        public void setTotalLinks(long totalLinks) { this.totalLinks = totalLinks; }
        public long getTotalRedirects() { return totalRedirects; }
        public void setTotalRedirects(long totalRedirects) { this.totalRedirects = totalRedirects; }
    }
}
