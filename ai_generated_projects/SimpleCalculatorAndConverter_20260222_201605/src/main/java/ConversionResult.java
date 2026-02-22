public class ConversionResult {
    private final double convertedValue;
    private final String error;

    public ConversionResult(double convertedValue, String error) {
        this.convertedValue = convertedValue;
        this.error = error;
    }

    public static ConversionResult success(double convertedValue) {
        return new ConversionResult(convertedValue, null);
    }

    public static ConversionResult error(String errorMessage) {
        return new ConversionResult(0.0, errorMessage);
    }

    public double getConvertedValue() {
        return convertedValue;
    }

    public String getError() {
        return error;
    }

    public boolean hasError() {
        return error != null && !error.isEmpty();
    }
}