package ch.psi.athos;

import ch.psi.utils.Arr;

/**
 *
 */
public class Histogram {
    
    final double[] counts;
    final double[] x;
    final double min;
    final double max;
    final int bins;
    
    
    Histogram (double[] counts, double[] x, double min, double max, int bins){
        this.counts = counts;
        this.x = x;
        this.min = min;
        this.max = max;
        this.bins = bins;
    }
    
    public static Histogram calc(double[] data, Double min, Double max, Integer bins) {
        if (data==null){
            return null;
        }
        if (min == null){
            min = (Double) Arr.getMin(data);
        }
        if (max == null){
            max = (Double) Arr.getMax(data);
        }
        if (bins == null){
            bins = 100;
        }
        final double binSize = (max - min) / bins;
        
        final double[] y = new double[bins];
        final double[] x = new double[bins];
        for (int i=0;i<bins; i++){
            x[i] = min + binSize * i;
        }
        
        for (double d : data) {                                
            int bin = (int) ((d - min) / binSize);
            if ((bin >= 0) && (bin < bins)) {
                y[bin] += 1;
            }            
        }
        return new Histogram(y,x, min, max, bins);
    }
}
