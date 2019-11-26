package ch.psi.athos;

import ch.psi.utils.Arr;

/**
 *
 */
public class Histogram {
    
    final double[] counts;
    final double[] x;
    
    Histogram (double[] counts, double[] x){
        this.counts = counts;
        this.x = x;
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
            x[i] = binSize * i;
        }
        
        for (double d : data) {                                
            int bin = (int) ((d - min) / binSize);
            if ((bin >= 0) && (bin < bins)) {
                y[bin] += 1;
            }            
        }
        return new Histogram(y,x);
    }
}
