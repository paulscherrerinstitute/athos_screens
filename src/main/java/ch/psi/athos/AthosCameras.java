package ch.psi.athos;

import ch.psi.pshell.bs.PipelineServer;
import ch.psi.pshell.bs.StreamValue;
import ch.psi.pshell.core.CommandSource;
import ch.psi.pshell.device.Device;
import ch.psi.pshell.device.DeviceAdapter;
import ch.psi.pshell.imaging.Overlay;
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
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

/**
 *
 */
public class AthosCameras extends Panel {
    
    PipelineServer imagePipeline;
    PipelineServer dataPipeline;
    //String serverUrl = "localhost:
    Overlay errorOverlay;
    String imageInstanceName;
    String dataInstanceName;
    String cameraName;
    String persistFile = "{context}/AthosCameras";    

    final static  String CAMERA_DEVICE_NAME = "AthosCamera";
    static String pipelineSuffixData = "_acd";
    static String pipelineSuffixImage = "_aci";
    static double imageFrameRate = 2.1;
    
    boolean dataChanged;
    
    boolean persisting;
    
    public AthosCameras() {
        initComponents();
        labelRecording.setVisible(false);
        buttonOpen.setEnabled(false);
        viewer.setPipelineNameFormat("%s" + pipelineSuffixImage);
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
        viewer.setPersistenceFile(Paths.get(getContext().getSetup().expandPath(persistFile)).toString());
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

    String getImagePipeline(){
        if (cameraName==null){
            return null;
        }
        return cameraName + pipelineSuffixImage;
    }
    
    String getDataPipeline(){
        if (cameraName==null){
            return null;
        }
        return cameraName + pipelineSuffixData;
    }
    
    String getDataPipelineInstance(){
        if (cameraName==null){
            return null;
        }
        return cameraName + pipelineSuffixData + "1";
    }
    
    
    void setDataFields(List<String> fields) throws IOException{
        if (cameraName!=null){
            HashMap<String, Object> config = new HashMap<>();
            config.put("camera_name", cameraName);
            config.put("include", fields.toArray(new String[0]));
            dataPipeline.setInstanceConfig(config);
            dataPipeline.savePipelineConfig(cameraName, config);        
            dataChanged = true;
        }
    }

    
    void setCamera(String cameraName) throws IOException, InterruptedException {
        System.out.println("Initializing: " + cameraName);
        
        boolean changed = !String.valueOf(cameraName).equals(this.cameraName);
        this.cameraName = cameraName;


        textCamera.setText((cameraName == null) ? "" : cameraName);
        if (cameraName == null) {
            return;
        }        

        System.out.println("Setting camera: " + cameraName );
        try{
                       
            String pipelineName = getImagePipeline(); 
            System.out.println("Creating pipeline: " + pipelineName);
            HashMap<String, Object> config = new HashMap<>();
            config.put("camera_name", cameraName);
            config.put("name", pipelineName);
            config.put("function", "transparent");
            config.put("max_frame_rate" , imageFrameRate);
            viewer.setStream(config);  
            
            dataPipeline = new PipelineServer("Data pipeline", viewer.getServer());
            dataPipeline.initialize();
            dataPipeline.assertInitialized();
            System.out.println("Data pipeline initialization OK");
            
            pipelineName = getDataPipeline();
            dataInstanceName = getDataPipelineInstance();
            if (!dataPipeline.getPipelines().contains(pipelineName)) {
                System.out.println("Creating pipeline: " + pipelineName);
                config = new HashMap<>();
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
        } finally {
            onTimer();
        }
    }
    
    String getDoubleStr(StreamValue sv, String id){
        return String.format("%1.4f", sv.getValue(id));
    }
    
    void updateData(StreamValue value){        
        DefaultTableModel model = (DefaultTableModel) table.getModel();
        List<String> ids = value.getIdentifiers();
        List values = value.getValues();
        if (dataChanged){
            //Collections.sort(ids, c);
            dataChanged = false;
        }
        if (model.getRowCount()!=ids.size()){
            model.setRowCount(ids.size());
            for (int i=0; i<ids.size();i++){
                model.setValueAt(ids.get(i), i, 0);
            }
        }
        for (int i=0; i<values.size();i++){
            Object val = values.get(i);
            if (val instanceof Double){
                val =  String.format("%1.4f", val);
            }
            model.setValueAt(val, i, 1);
        }
    }    
    
    MonitorScan scan;
    
    void startRecording() throws Exception{
        System.out.println("startRecording");
        getContext().startExecution(CommandSource.plugin, null, cameraName,null, false);
        getContext().setExecutionPar("name", cameraName);
        getContext().setExecutionPar("layout", "default");
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
        args = Arr.append(new String[]{ "-l", "-k", "-q", "-b", "-e", "-g", "-n", "-d", "-sbar", "-dlaf",          
                                        "-p=ch.psi.athos.AthosCameras", 
                                      }, 
                          args);
        App.main(args);
        if (App.hasArgument("frameRate")){
            imageFrameRate = Double.parseDouble(App.getArgumentValue("frameRate"));
        }
    }
    
    
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jPanel1 = new javax.swing.JPanel();
        buttonRec = new javax.swing.JToggleButton();
        buttonStop = new javax.swing.JButton();
        jPanel3 = new javax.swing.JPanel();
        labelRecording = new javax.swing.JLabel();
        buttonOpen = new javax.swing.JButton();
        scrollFile = new javax.swing.JScrollPane();
        textFile = new javax.swing.JTextField();
        jPanel2 = new javax.swing.JPanel();
        jScrollPane1 = new javax.swing.JScrollPane();
        table = new javax.swing.JTable();
        buttonSelect = new javax.swing.JButton();
        jPanel4 = new javax.swing.JPanel();
        textCamera = new javax.swing.JTextField();
        viewer = new ch.psi.pshell.bs.StreamCameraViewer();

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

        table.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Name", "Value"
            }
        ) {
            Class[] types = new Class [] {
                java.lang.String.class, java.lang.Object.class
            };
            boolean[] canEdit = new boolean [] {
                false, false
            };

            public Class getColumnClass(int columnIndex) {
                return types [columnIndex];
            }

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        jScrollPane1.setViewportView(table);

        buttonSelect.setText("Select");
        buttonSelect.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                buttonSelectActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel2Layout = new javax.swing.GroupLayout(jPanel2);
        jPanel2.setLayout(jPanel2Layout);
        jPanel2Layout.setHorizontalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel2Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jScrollPane1, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, 281, Short.MAX_VALUE)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel2Layout.createSequentialGroup()
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addComponent(buttonSelect)))
                .addContainerGap())
        );
        jPanel2Layout.setVerticalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel2Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 0, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(buttonSelect)
                .addContainerGap())
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

        viewer.setLocalFit(java.lang.Boolean.TRUE);
        viewer.setServer("localhost:8889");
        viewer.setShowFit(true);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addComponent(viewer, javax.swing.GroupLayout.DEFAULT_SIZE, 467, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
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
                .addComponent(jPanel4, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel2, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap())
            .addComponent(viewer, javax.swing.GroupLayout.DEFAULT_SIZE, 413, Short.MAX_VALUE)
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

    private void buttonSelectActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_buttonSelectActionPerformed
        try{
            String instance = getDataPipelineInstance();
            if (instance != null){
                DataSelector dlg = new DataSelector(getTopLevel(), true);            
                dlg.setLocationRelativeTo(getTopLevel());
                dlg.set(viewer.getServer(), instance);
                dlg.setVisible(true);
                if (dlg.getResult()){
                    setDataFields(dlg.selected);
                }
            }
        } catch (Exception ex){
            this.showException(ex);
        }
    }//GEN-LAST:event_buttonSelectActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton buttonOpen;
    private javax.swing.JToggleButton buttonRec;
    private javax.swing.JButton buttonSelect;
    private javax.swing.JButton buttonStop;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JPanel jPanel4;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JLabel labelRecording;
    private javax.swing.JScrollPane scrollFile;
    private javax.swing.JTable table;
    private javax.swing.JTextField textCamera;
    private javax.swing.JTextField textFile;
    private ch.psi.pshell.bs.StreamCameraViewer viewer;
    // End of variables declaration//GEN-END:variables
}
