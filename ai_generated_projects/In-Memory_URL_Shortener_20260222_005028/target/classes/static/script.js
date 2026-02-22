// Copy to clipboard functionality
function copyToClipboard(elementId) {
    const element = document.getElementById(elementId);
    if (!element) {
        console.error('Element not found:', elementId);
        return;
    }
    
    // Create a temporary textarea to hold the text
    const tempTextArea = document.createElement('textarea');
    tempTextArea.value = element.textContent || element.innerText;
    document.body.appendChild(tempTextArea);
    
    // Select the text and copy it
    tempTextArea.select();
    tempTextArea.setSelectionRange(0, 99999); // For mobile devices
    
    try {
        const successful = document.execCommand('copy');
        if (successful) {
            // Show feedback to user
            const originalText = element.textContent;
            element.textContent = 'Copied!';
            setTimeout(() => {
                element.textContent = originalText;
            }, 2000);
        } else {
            console.error('Copy command was unsuccessful');
        }
    } catch (err) {
        console.error('Unable to copy text to clipboard', err);
    }
    
    // Remove the temporary textarea
    document.body.removeChild(tempTextArea);
}

// Form validation function
function validateForm() {
    const urlInput = document.getElementById('longUrl');
    const errorMessage = document.getElementById('error-message');
    
    if (!urlInput) {
        console.error('URL input field not found');
        return false;
    }
    
    const urlValue = urlInput.value.trim();
    
    // Clear previous error message
    if (errorMessage) {
        errorMessage.style.display = 'none';
    }
    
    // Check if URL is empty
    if (!urlValue) {
        if (errorMessage) {
            errorMessage.textContent = 'Please enter a URL';
            errorMessage.style.display = 'block';
        }
        return false;
    }
    
    // Regular expression for URL validation
    const urlPattern = /^(https?|ftp):\/\/[\w\-]+(\.[\w\-]+)+([\w\-\.,@?^=%&:\/~\+#]*[\w\-\@?^=%&\/~\+#])?$/;
    
    if (!urlPattern.test(urlValue)) {
        if (errorMessage) {
            errorMessage.textContent = 'Please enter a valid URL (e.g., https://example.com)';
            errorMessage.style.display = 'block';
        }
        return false;
    }
    
    // If all validations pass
    return true;
}

// Add event listener to the form submit button
document.addEventListener('DOMContentLoaded', function() {
    const shortenForm = document.getElementById('shortenForm');
    if (shortenForm) {
        shortenForm.addEventListener('submit', function(event) {
            if (!validateForm()) {
                event.preventDefault(); // Prevent form submission if validation fails
            }
        });
    }
});