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
public class HistogramDevice extends ReadonlyRegisterBase<Histogram> implements ReadonlyRegisterArray<Histogram>, IntegerType {

    final ReadonlyRegisterBase source;
    final int numberOfSamples;
    final Double min;
    final Double max;
    final Integer bins;
    final List<Double> samples = new ArrayList<>();
    DeviceListener sourceListener;

    Histogram histogram;

    public HistogramDevice(String name, ReadonlyRegisterBase source, int window, Double min, Double max, Integer bins) {
        super(name);
        setParent(source);
        this.source = source;
        this.numberOfSamples = window;
        this.min = min;
        this.max = max;
        this.bins = bins;
    }

    public HistogramDevice(String name, ReadonlyRegisterNumber source, int window, Double min, Double max, Integer bins) {
        this(name, (ReadonlyRegisterBase) source, window, min, max, bins);
    }

    public HistogramDevice(String name, ReadonlyRegisterArray source, Double min, Double max, Integer bins) {
        this(name, (ReadonlyRegisterBase) source, 1, min, max, bins);
    }

    public Device getSource() {
        return source;
    }

    public double[] getX() {
        return histogram.x;
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public double getBins() {
        return bins;
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
                Logger.getLogger(HistogramDevice.class.getName()).log(Level.WARNING, null, ex);
            }
        }
    }

    void addSample(Object sample) {
        if (!this.isInitialized()) {
            return;
        }
        synchronized (samples) {
            try {
                Object data;
                if (sample instanceof Number) {
                    samples.add(((Number) sample).doubleValue());
                    while (samples.size() > numberOfSamples) {
                        samples.remove(0);
                    }
                    data = samples;
                } else if (sample.getClass().isArray()) {
                    data = sample;
                } else {
                    return;
                }
                setCache( Histogram.calc((double[]) Convert.toPrimitiveArray(data, Double.class), min, max, bins));
            } catch (Exception ex) {
                Logger.getLogger(HistogramDevice.class.getName()).log(Level.WARNING, null, ex);
            }
        }

    }

    @Override
    protected Histogram doRead() throws IOException, InterruptedException {
        if (!isMonitored()) {
            readSample();
        }
        return take();
    }

    @Override
    public int getSize() {
        return histogram.bins;
    }
}
