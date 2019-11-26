package ch.psi.athos;

import ch.psi.pshell.device.Device;
import ch.psi.pshell.plot.LinePlotSeries;
import ch.psi.pshell.plot.Plot;
import ch.psi.pshell.swing.DevicePanel;

/**
 *
 */
public class HistogramDevicePanel extends DevicePanel {

    LinePlotSeries series;
    
    public HistogramDevicePanel() {
        initComponents();
        plot.getAxis(Plot.AxisId.X).setLabel("");
        plot.getAxis(Plot.AxisId.Y).setLabel("");
    }
    
    @Override
    public HistogramDevice getDevice() {
        return (HistogramDevice) super.getDevice();
    }
        
    @Override
    public void setDevice(Device device) {
        plot.clear();
        super.setDevice(device);
        series = new LinePlotSeries(device.getName() + " Histogram");
        plot.addSeries(series);
    }
    
    protected void onDeviceCacheChanged(Object value, Object former, long timestamp, boolean valueChange) {
        series.setData(getDevice().getX(), (double[]) value);
    }
    
    

    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        plot = new ch.psi.pshell.plot.LinePlotJFree();

        plot.setStyle(ch.psi.pshell.plot.LinePlot.Style.Step);
        plot.setTitle("");

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(plot, javax.swing.GroupLayout.DEFAULT_SIZE, 315, Short.MAX_VALUE)
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(plot, javax.swing.GroupLayout.DEFAULT_SIZE, 111, Short.MAX_VALUE)
        );
    }// </editor-fold>//GEN-END:initComponents


    // Variables declaration - do not modify//GEN-BEGIN:variables
    private ch.psi.pshell.plot.LinePlotJFree plot;
    // End of variables declaration//GEN-END:variables
}
