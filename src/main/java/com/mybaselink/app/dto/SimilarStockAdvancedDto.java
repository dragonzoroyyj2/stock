package com.mybaselink.app.dto;

import java.util.List;

public class SimilarStockAdvancedDto {
    private String file;
    private double distance;
    private List<String> dates;
    private List<Double> prices;

    // Getter & Setter
    public String getFile() { return file; }
    public void setFile(String file) { this.file = file; }
    public double getDistance() { return distance; }
    public void setDistance(double distance) { this.distance = distance; }
    public List<String> getDates() { return dates; }
    public void setDates(List<String> dates) { this.dates = dates; }
    public List<Double> getPrices() { return prices; }
    public void setPrices(List<Double> prices) { this.prices = prices; }
}
