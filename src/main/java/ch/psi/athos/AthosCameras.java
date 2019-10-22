package ch.psi.athos;

import ch.psi.pshell.bs.PipelineServer;
import ch.psi.pshell.bs.StreamValue;
import ch.psi.pshell.core.CommandSource;
import ch.psi.pshell.device.Device;
import ch.psi.pshell.device.DeviceAdapter;
import ch.psi.pshell.imaging.Overlay;
import ch.psi.pshell.imaging.Overlays;
import ch.psi.pshell.scan.MonitorScan;
import ch.psi.pshell.swing.DataPanel;
import ch.psi.pshell.ui.App;
import ch.psi.pshell.ui.Panel;
import ch.psi.utils.Arr;
import ch.psi.utils.State;
import ch.psi.utils.Threading;
import ch.psi.utils.swing.MainFrame;
import ch.psi.utils.swing.SwingUtils;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
//import oracle.jdbc.driver;

/**
 *
 */
public class AthosCameras extends Panel {
    
    PipelineServer imagePipeline;
    PipelineServer dataPipeline;
    String serverUrl;
    Overlay errorOverlay;
    String imageInstanceName;
    String dataInstanceName;
    String cameraName;
    String persistFile = "{context}/AthosCameras";

    final String CAMERA_DEVICE_NAME = "AthosCamera";
    String pipelineSuffixData = "_acd";
    String pipelineSuffixImage = "_aci";
    
    boolean persisting;
    
    public AthosCameras() {
        initComponents();
        labelRecording.setVisible(false);
        buttonOpen.setEnabled(false);
    }
    
    ImageIcon getIcon(String name){
        return new ImageIcon(ch.psi.pshell.ui.App.class.getResource("/ch/psi/pshell/ui/" + (MainFrame.isDark() ? "dark/": "") + name + ".png"));    
    }

    //Overridable callbacks
    @Override
    public void onInitialize(int runCount) {
        if (App.hasArgument("cam")) {
            try {
                setCamera(App.getArgumentValue("cam"));
            } catch (Exception ex) {
                Logger.getLogger(AthosCameras.class.getName()).log(Level.SEVERE, null, ex);
            } 
        }
        renderer.setPersistenceFile(Paths.get(getContext().getSetup().expandPath(persistFile)));
    }

    @Override
    public void onStateChange(State state, State former) {

    }

    @Override
    public void onExecutedFile(String fileName, Object result) {
    }

    
    //Callback to perform update - in event thread
    @Override
    protected void doUpdate() {
    }

    
    PipelineServer newServer() throws IOException {
        if (serverUrl != null) {
            System.out.println("Connecting to server: " + serverUrl);
            imagePipeline = new PipelineServer(CAMERA_DEVICE_NAME, serverUrl);
        } else {
            System.out.println("Connecting to server");
            imagePipeline = new PipelineServer(CAMERA_DEVICE_NAME);
        }
        return imagePipeline;
    }    

    void setCamera(String cameraName) throws IOException, InterruptedException {
        System.out.println("Initializing: " + cameraName);
        renderer.setDevice(null);
        renderer.setShowReticle(false);
        renderer.clear();
        renderer.resetZoom();

        boolean changed = !String.valueOf(cameraName).equals(this.cameraName);
        this.cameraName = cameraName;


        textCamera.setText((cameraName == null) ? "" : cameraName);
        if (cameraName == null) {
            return;
        }
        

        System.out.println("Setting camera: " + cameraName );
        try{
            imagePipeline = newServer();
            imagePipeline.getConfig().flipHorizontally = false;
            imagePipeline.getConfig().flipVertically = false;
            imagePipeline.getConfig().rotation = 0.0;
            imagePipeline.getConfig().roiX = 0;
            imagePipeline.getConfig().roiY = 0;
            imagePipeline.getConfig().roiWidth = -1;
            imagePipeline.getConfig().roiHeight = -1;
            imagePipeline.getConfig().save();
            imagePipeline.initialize();
            imagePipeline.assertInitialized();
            System.out.println("Image pipeline initialization OK");

            String pipelineName = cameraName + pipelineSuffixImage;
            imageInstanceName = cameraName + pipelineSuffixImage + "1";
            if (!imagePipeline.getPipelines().contains(pipelineSuffixImage)) {
                System.out.println("Creating pipeline: " + pipelineName);
                HashMap<String, Object> config = new HashMap<>();
                config.put("camera_name", cameraName);
                //config.put("include", new String[]{"image", "width", "height"});
                config.put("function", "transparent");
                config.put("max_frame_rate" , 2.1);
                //server.createFromConfig(config, pipelineName);
                imagePipeline.savePipelineConfig(pipelineName, config);
            }
            imagePipeline.start(pipelineName, imageInstanceName);            
            renderer.setDevice(imagePipeline);
            
            
            dataPipeline = newServer();
            //dataPipeline.getConfig().copyFrom(imagePipeline.getConfig());
            dataPipeline.initialize();
            dataPipeline.assertInitialized();
            System.out.println("Data pipeline initialization OK");
            
            pipelineName = cameraName + pipelineSuffixData;
            dataInstanceName = cameraName + pipelineSuffixData + "1";
            if (!dataPipeline.getPipelines().contains(pipelineSuffixData)) {
                System.out.println("Creating pipeline: " + pipelineName);
                HashMap<String, Object> config = new HashMap<>();
                config.put("camera_name", cameraName);
                config.put("include", new String[]{"x_center_of_mass", "y_center_of_mass",
                                                   "x_fit_mean", "y_fit_mean"});
                //server.createFromConfig(config, pipelineName);
                dataPipeline.savePipelineConfig(pipelineName, config);
            }
            dataPipeline.start(pipelineName, dataInstanceName);                        
            
            dataPipeline.getStream().addListener(new DeviceAdapter() {
                @Override
                public void onCacheChanged(Device device, Object value, Object former, long timestamp, boolean arg4) {
                    updateData((StreamValue)value);                    
                }
            });
            
        } catch (Exception ex) {
            showException(ex);
            renderer.clearOverlays();
            if (renderer.getDevice() == null) {
                errorOverlay = new Overlays.Text(renderer.getPenErrorText(), ex.toString(), new Font("Verdana", Font.PLAIN, 12), new Point(20, 20));
                errorOverlay.setFixed(true);
                errorOverlay.setAnchor(Overlay.ANCHOR_VIEWPORT_TOP_LEFT);
                renderer.addOverlay(errorOverlay);
            }
        } finally {
            onTimer();
        }
    }
    
    String getDoubleStr(StreamValue sv, String id){
        return String.format("%1.4f", sv.getValue(id));
    }
    
    void updateData(StreamValue value){
        try{
            edit_x_center_of_mass.setText(getDoubleStr(value, "x_center_of_mass"));
        } catch (Exception ex){
            edit_x_center_of_mass.setText("");
        }
        try{
            edit_y_center_of_mass.setText(getDoubleStr(value, "y_center_of_mass"));
        } catch (Exception ex){
            edit_y_center_of_mass.setText("");
        }        
        try{
            edit_x_fit_mean.setText(getDoubleStr(value, "x_fit_mean"));
        } catch (Exception ex){
            edit_x_fit_mean.setText("");
        }
        try{
            edit_y_fit_mean.setText(getDoubleStr(value, "y_fit_mean"));
        } catch (Exception ex){
            edit_y_fit_mean.setText("");
        }
    }
    
    MonitorScan scan;
    
    void startRecording() throws Exception{
        System.out.println("startRecording");
        getContext().startExecution(CommandSource.plugin, null, cameraName,null, false);
        getContext().setExecutionPar("name", cameraName);
        getContext().setExecutionPar("open", true);
        scan=  new MonitorScan(dataPipeline.getStream(), dataPipeline.getStream().getReadables().toArray(new ch.psi.pshell.device.Readable[0]), -1, -1);
        Threading.getFuture(() ->scan.start());          
        textFile.setText(getContext().getExecutionPars().getPath());
        SwingUtilities.invokeLater(()->{
            scrollFile.getHorizontalScrollBar().setValue( scrollFile.getHorizontalScrollBar().getMaximum() );
        });
        buttonOpen.setEnabled(true);
    }
    
    void stopRecording() throws Exception{
        System.out.println("stopRecording");        
        scan.abort();        
        getContext().endExecution();
    }
    
    void openFile() throws Exception{
        String filename = textFile.getText();
        DataPanel panel = DataPanel.create(new File(filename));
        SwingUtils.showDialog(getTopLevel(), filename, new Dimension(600,400), panel);        
    }
    
    
    
    public static void main(String args[]) throws InterruptedException {
        args = Arr.append(new String[]{ "-l", "-k", "-q", "-b", "-e", "-g", "-n", "-d", "-sbar",      
                                        "-p=SfCamera.java", "-p=Inventory.java", "-p=CameraCalibrationDialog.java", 
                                        "-p=ch.psi.athos.AthosCameras", 
                                      }, 
                          args);
        App.main(args);  
    }
    
    
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        renderer = new ch.psi.pshell.imaging.Renderer();
        jPanel1 = new javax.swing.JPanel();
        buttonRec = new javax.swing.JToggleButton();
        buttonStop = new javax.swing.JButton();
        jPanel3 = new javax.swing.JPanel();
        labelRecording = new javax.swing.JLabel();
        buttonOpen = new javax.swing.JButton();
        scrollFile = new javax.swing.JScrollPane();
        textFile = new javax.swing.JTextField();
        jPanel2 = new javax.swing.JPanel();
        jLabel1 = new javax.swing.JLabel();
        jLabel2 = new javax.swing.JLabel();
        edit_y_center_of_mass = new javax.swing.JTextField();
        edit_x_center_of_mass = new javax.swing.JTextField();
        jLabel3 = new javax.swing.JLabel();
        edit_x_fit_mean = new javax.swing.JTextField();
        edit_y_fit_mean = new javax.swing.JTextField();
        jLabel4 = new javax.swing.JLabel();
        jPanel4 = new javax.swing.JPanel();
        textCamera = new javax.swing.JTextField();

        jPanel1.setBorder(javax.swing.BorderFactory.createTitledBorder("Data Recording"));

        buttonRec.setIcon(getIcon("Rec"));
        buttonRec.setToolTipText("Start Data Recording");
        buttonRec.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                buttonRecActionPerformed(evt);
            }
        });

        buttonStop.setIcon(getIcon("Stop"));
        buttonStop.setEnabled(false);
        buttonStop.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                buttonStopActionPerformed(evt);
            }
        });

        labelRecording.setFont(new java.awt.Font("Lucida Grande", 0, 12)); // NOI18N
        labelRecording.setForeground(new java.awt.Color(255, 0, 0));
        labelRecording.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        labelRecording.setText("RECORDING");

        javax.swing.GroupLayout jPanel3Layout = new javax.swing.GroupLayout(jPanel3);
        jPanel3.setLayout(jPanel3Layout);
        jPanel3Layout.setHorizontalGroup(
            jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel3Layout.createSequentialGroup()
                .addGap(0, 0, 0)
                .addComponent(labelRecording, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGap(0, 0, 0))
        );
        jPanel3Layout.setVerticalGroup(
            jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel3Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(labelRecording, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addContainerGap())
        );

        buttonOpen.setText("Open Data File");
        buttonOpen.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                buttonOpenActionPerformed(evt);
            }
        });

        scrollFile.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
        scrollFile.setVerticalScrollBarPolicy(javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);

        textFile.setEditable(false);
        scrollFile.setViewportView(textFile);

        javax.swing.GroupLayout jPanel1Layout = new javax.swing.GroupLayout(jPanel1);
        jPanel1.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(scrollFile, javax.swing.GroupLayout.PREFERRED_SIZE, 281, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(jPanel1Layout.createSequentialGroup()
                        .addComponent(buttonRec)
                        .addGap(2, 2, 2)
                        .addComponent(buttonStop)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jPanel3, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel1Layout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(buttonOpen)))
                .addContainerGap())
        );
        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jPanel3, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                        .addComponent(buttonRec, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(buttonStop, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(scrollFile, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(buttonOpen)
                .addContainerGap())
        );

        jPanel2.setBorder(javax.swing.BorderFactory.createTitledBorder("Data Fields"));

        jLabel1.setText("x_center_of_mass:");

        jLabel2.setText("y_center_of_mass:");

        edit_y_center_of_mass.setEditable(false);
        edit_y_center_of_mass.setHorizontalAlignment(javax.swing.JTextField.TRAILING);

        edit_x_center_of_mass.setEditable(false);
        edit_x_center_of_mass.setHorizontalAlignment(javax.swing.JTextField.TRAILING);

        jLabel3.setText("x_fit_mean:");

        edit_x_fit_mean.setEditable(false);
        edit_x_fit_mean.setHorizontalAlignment(javax.swing.JTextField.TRAILING);

        edit_y_fit_mean.setEditable(false);
        edit_y_fit_mean.setHorizontalAlignment(javax.swing.JTextField.TRAILING);

        jLabel4.setText("y_fit_mean");

        javax.swing.GroupLayout jPanel2Layout = new javax.swing.GroupLayout(jPanel2);
        jPanel2.setLayout(jPanel2Layout);
        jPanel2Layout.setHorizontalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel2Layout.createSequentialGroup()
                .addContainerGap(19, Short.MAX_VALUE)
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(jLabel1)
                    .addComponent(jLabel2)
                    .addComponent(jLabel3)
                    .addComponent(jLabel4))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(edit_y_center_of_mass, javax.swing.GroupLayout.PREFERRED_SIZE, 124, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(edit_x_center_of_mass, javax.swing.GroupLayout.PREFERRED_SIZE, 124, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(edit_x_fit_mean, javax.swing.GroupLayout.PREFERRED_SIZE, 124, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(edit_y_fit_mean, javax.swing.GroupLayout.PREFERRED_SIZE, 124, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap(20, Short.MAX_VALUE))
        );
        jPanel2Layout.setVerticalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel2Layout.createSequentialGroup()
                .addGap(30, 30, 30)
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel1)
                    .addComponent(edit_x_center_of_mass, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel2)
                    .addComponent(edit_y_center_of_mass, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel3)
                    .addComponent(edit_x_fit_mean, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel4)
                    .addComponent(edit_y_fit_mean, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap(33, Short.MAX_VALUE))
        );

        jPanel4.setBorder(javax.swing.BorderFactory.createTitledBorder("Camera"));

        textCamera.setHorizontalAlignment(javax.swing.JTextField.CENTER);

        javax.swing.GroupLayout jPanel4Layout = new javax.swing.GroupLayout(jPanel4);
        jPanel4.setLayout(jPanel4Layout);
        jPanel4Layout.setHorizontalGroup(
            jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel4Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(textCamera)
                .addContainerGap())
        );
        jPanel4Layout.setVerticalGroup(
            jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel4Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(textCamera, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap())
        );

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(renderer, javax.swing.GroupLayout.DEFAULT_SIZE, 505, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jPanel1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                        .addComponent(jPanel2, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(jPanel4, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(renderer, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(javax.swing.GroupLayout.Alignment.LEADING, layout.createSequentialGroup()
                        .addComponent(jPanel4, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jPanel2, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(jPanel1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addContainerGap())
        );
    }// </editor-fold>//GEN-END:initComponents

    private void buttonRecActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_buttonRecActionPerformed
        try{
            buttonStop.setEnabled(buttonRec.isSelected());
            labelRecording.setVisible(buttonRec.isSelected());
            if (buttonRec.isSelected()){
                startRecording();
            } else {
                stopRecording();
            }
        } catch (Exception ex){
            this.showException(ex);
        }
    }//GEN-LAST:event_buttonRecActionPerformed

    private void buttonStopActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_buttonStopActionPerformed
        buttonRec.setSelected(false);
        buttonRecActionPerformed(null);
    }//GEN-LAST:event_buttonStopActionPerformed

    private void buttonOpenActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_buttonOpenActionPerformed
        try{
            openFile();
        } catch (Exception ex){
            this.showException(ex);
        }
    }//GEN-LAST:event_buttonOpenActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton buttonOpen;
    private javax.swing.JToggleButton buttonRec;
    private javax.swing.JButton buttonStop;
    private javax.swing.JTextField edit_x_center_of_mass;
    private javax.swing.JTextField edit_x_fit_mean;
    private javax.swing.JTextField edit_y_center_of_mass;
    private javax.swing.JTextField edit_y_fit_mean;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JPanel jPanel4;
    private javax.swing.JLabel labelRecording;
    private ch.psi.pshell.imaging.Renderer renderer;
    private javax.swing.JScrollPane scrollFile;
    private javax.swing.JTextField textCamera;
    private javax.swing.JTextField textFile;
    // End of variables declaration//GEN-END:variables
}
