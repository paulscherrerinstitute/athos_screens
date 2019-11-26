package ch.psi.athos;

import ch.psi.pshell.device.Device;
import ch.psi.pshell.device.DeviceAdapter;
import ch.psi.pshell.device.DeviceListener;
import ch.psi.pshell.device.Readable.IntegerType;
import ch.psi.pshell.device.ReadonlyRegister.ReadonlyRegisterArray;
import ch.psi.pshell.device.ReadonlyRegisterBase;
import ch.psi.utils.Convert;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 */
public class HistogramDevice extends ReadonlyRegisterBase<int[]> implements ReadonlyRegisterArray<int[]> , IntegerType{
    final ReadonlyRegisterBase source;
    final int window;
    DeviceListener sourceListener;
    final List<Double> samples = new ArrayList<>();
    double[] x;
    
            
    public HistogramDevice (String name, ReadonlyRegisterBase source, int window) {
        super(name);
        setParent(source);
        this.source = source;
        this.window = window;
    }
    
    public ReadonlyRegisterBase getSource() {
        return source;
    }    
    
    public int getWindow() {
        return window;
    }        
    
    
    public double[] getX(){
        return x;
    }
    
    @Override
    protected void doInitialize() throws IOException, InterruptedException {
        source.removeListener(sourceListener);
        sourceListener = null;
        super.doInitialize();
        
        sourceListener = new DeviceAdapter() {
            @Override
            public void onCacheChanged(Device device, Object value, Object former, long timestamp, boolean valueChange) {
                addSample(value);
            }
        };        
    }
    
    @Override
    protected void doSetMonitored(boolean value) {
        if (value) {
           source.addListener(sourceListener);
        } else {
           source.removeListener(sourceListener);
        }
    }

    void readSample() {
        if (this.isInitialized()) {
            try {
                addSample(source.read());
            } catch (Exception ex) {
                Logger.getLogger(HistogramDevice.class.getName()).log(Level.WARNING,  null, ex);
            }
        }
    }
    
    void addSample(Object sample) {
        if (!this.isInitialized()) {
            return;
        }
        synchronized (samples) {
            try {                
                samples.add(((Number)sample).doubleValue());
                while (samples.size() > window) {
                    samples.remove(0);
                }        
                Histogram histogram = Histogram.calc((double[]) Convert.toPrimitiveArray(samples), null, null, null);                        
                setCache(histogram.counts);
                x = histogram.x;
            } catch (Exception ex) {
                Logger.getLogger(HistogramDevice.class.getName()).log(Level.WARNING,  null, ex);
            }
        }

    }        
    

    @Override
    protected int[] doRead() throws IOException, InterruptedException {
        if (!isMonitored()) {
            readSample();
        }
        return take();
    }

    @Override
    public int getSize() {
        return window;
    }
}
