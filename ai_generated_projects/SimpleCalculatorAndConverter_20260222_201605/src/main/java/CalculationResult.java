public class CalculationResult {
    private final Double result;
    private final String error;

    public CalculationResult(Double result, String error) {
        this.result = result;
        this.error = error;
    }

    public static CalculationResult success(Double result) {
        return new CalculationResult(result, null);
    }

    public static CalculationResult failure(String error) {
        return new CalculationResult(null, error);
    }

    public Double getResult() {
        return result;
    }

    public String getError() {
        return error;
    }

    public boolean hasError() {
        return error != null;
    }
}