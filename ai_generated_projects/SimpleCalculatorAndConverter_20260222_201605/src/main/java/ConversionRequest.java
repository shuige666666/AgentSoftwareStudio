public class ConversionRequest {
    private double value;
    private String fromUnit;
    private String toUnit;
    private String type;

    public ConversionRequest() {
    }

    public ConversionRequest(double value, String fromUnit, String toUnit, String type) {
        this.value = value;
        this.fromUnit = fromUnit;
        this.toUnit = toUnit;
        this.type = type;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public String getFromUnit() {
        return fromUnit;
    }

    public void setFromUnit(String fromUnit) {
        this.fromUnit = fromUnit;
    }

    public String getToUnit() {
        return toUnit;
    }

    public void setToUnit(String toUnit) {
        this.toUnit = toUnit;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}