package ch.psi.athos;

import ch.psi.pshell.bs.PipelineServer;
import ch.psi.pshell.bs.StreamValue;
import ch.psi.pshell.core.CommandSource;
import ch.psi.pshell.core.Context;
import ch.psi.pshell.device.Device;
import ch.psi.pshell.device.DeviceAdapter;
import ch.psi.pshell.device.DeviceListener;
import ch.psi.pshell.device.Readable.ReadableArray;
import ch.psi.pshell.device.Readable.ReadableNumber;
import ch.psi.pshell.device.ReadableRegister.ReadableRegisterArray;
import ch.psi.pshell.device.ReadableRegister.ReadableRegisterNumber;
import ch.psi.pshell.epics.ChannelString;
import ch.psi.pshell.imaging.Overlay;
import ch.psi.pshell.scan.MonitorScan;
import ch.psi.pshell.swing.DataPanel;
import ch.psi.pshell.swing.DeviceValueChart;
import ch.psi.pshell.ui.App;
import ch.psi.pshell.ui.Panel;
import ch.psi.utils.Arr;
import ch.psi.utils.Convert;
import ch.psi.utils.State;
import ch.psi.utils.Str;
import ch.psi.utils.Threading;
import ch.psi.utils.swing.MainFrame;
import ch.psi.utils.swing.SwingUtils;
import java.awt.Component;
import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

/**
 *
 */
public class AthosCameras extends Panel {
    final Logger logger;
    
    PipelineServer imagePipeline;
    PipelineServer dataPipeline;
    PipelineServer savePipeline;
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
    
    final DefaultTableModel model;
    
    Map<String, JDialog> deviceDialogs = new HashMap<>();
    Map<String, Object> dataPipelineConfig;
    
    String remoteData;
    
    final List<String> fileHistory = new ArrayList<>();
    final List<String> fileRemHistory = new ArrayList<>();
    
    ChannelString channelCameraName;
    HardwarePanel hardwarePanel;
    
    public AthosCameras() {
        initComponents();
        model = (DefaultTableModel) table.getModel();
        labelRecording.setVisible(false);
        labelSrvRecording.setVisible(false);
        buttonOpen.setEnabled(false);
        buttonSrvOpen.setEnabled(false);
        if (App.hasArgument("pipeline_server")) {
            viewer.setServerUrl(App.getArgumentValue("pipeline_server"));
        }        
        viewer.setPipelineNameFormat("%s" + pipelineSuffixImage);
        setPersistedComponents(new Component[]{});
        remoteData = App.getArgumentValue("remote_data");
        buttonSrvOpen.setVisible((remoteData!=null) && (new File(remoteData)).isDirectory());
        panelSrvRec.setVisible(remoteData!=null);        
        logger = Logger.getLogger(AthosCameras.class.getName());
        hardwarePanel = new HardwarePanel();
        viewer.getCustomPanel().add(hardwarePanel);
    }
        
    
    ImageIcon getIcon(String name){
        return MainFrame.searchIcon(name);
    }

    //Overridable callbacks
    @Override
    public void onInitialize(int runCount) {
        try {
            viewer.setPersistenceFile(Paths.get(getContext().getSetup().expandPath(persistFile)).toString());
            if (App.hasArgument("cam")) {
                    setCamera(App.getArgumentValue("cam"));
            } else if (App.hasArgument("channel")){
                channelCameraName = new  ChannelString ("Channel Selector", App.getArgumentValue("channel"));
                channelCameraName.setMonitored(true);
                channelCameraName.addListener(new DeviceAdapter(){
                    public void onValueChanged(Device device, Object value, Object former){
                        SwingUtilities.invokeLater(()->{
                            try{
                                setCamera(String.valueOf(value));
                            } catch (Exception ex) {
                                logger.log(Level.SEVERE, null, ex);
                                showException(ex);
                            } 
                        });                        
                    }
                });

                channelCameraName.initialize();
                channelCameraName.update();
                addDevice(channelCameraName);
            }
            updateButtons();
            startTimer(1000);            
        } catch (Exception ex) {
            logger.log(Level.SEVERE, null, ex);
            showException(ex);
        } 
    }

    @Override
    public void onStateChange(State state, State former) {
        if (state==state.Closing){
            try {
                stopSrvRecording();
            } catch (Exception ex) {
                logger.log(Level.SEVERE, null, ex);
            }
        }
    }
    
    @Override
    protected void onTimer() {
        try{
            if (hardwarePanel!=null){
                hardwarePanel.onTimer();
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING, null, ex);
        }
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
            dataPipeline.savePipelineConfig(getDataPipeline(), config);        
            dataChanged = true;
        }
    }

    
    void setCamera(String cameraName) throws IOException, InterruptedException {
         logger.info("Initializing: " + cameraName);
        
        boolean changed = !String.valueOf(cameraName).equals(this.cameraName);
        this.cameraName = cameraName;
        model.setRowCount(0);
        updateButtons();
        if (changed){
            if (hardwarePanel!=null){
                hardwarePanel.setCamera(null);
            }
        }
        if (cameraName == null) {
            return;
        }        

        logger.info("Setting camera: " + cameraName );
        try{
                       
            String pipelineName = getImagePipeline(); 
            logger.info("Creating pipeline: " + pipelineName);
            HashMap<String, Object> config = new HashMap<>();
            config.put("camera_name", cameraName);
            config.put("name", pipelineName);
            config.put("function", "transparent");
            config.put("max_frame_rate" , imageFrameRate);
            viewer.setStream(config);  

            dataPipeline = new PipelineServer("image", viewer.getServerUrl()); 
            dataPipeline.initialize();
            dataPipeline.assertInitialized();
            logger.info("Data pipeline initialization OK");
            
            pipelineName = getDataPipeline();
            dataInstanceName = getDataPipelineInstance();
            if (!dataPipeline.getPipelines().contains(pipelineName)) {
                logger.info("Creating pipeline: " + pipelineName);
                dataPipelineConfig = new HashMap<>();
                dataPipelineConfig.put("camera_name", cameraName);
                dataPipelineConfig.put("include", new String[]{"x_center_of_mass", "y_center_of_mass",
                                                   "x_fit_mean", "y_fit_mean"});
                dataPipelineConfig.put("image_region_of_interest", viewer.getServer().getRoi());                
                //server.createFromConfig(config, pipelineName);
                dataPipeline.savePipelineConfig(pipelineName, dataPipelineConfig);
            } 
            dataPipelineConfig = dataPipeline.getConfig(pipelineName);
            dataPipeline.start(pipelineName, dataInstanceName);                  
            
            dataPipeline.getStream().addListener(new DeviceAdapter() {
                @Override
                public void onCacheChanged(Device device, Object value, Object former, long timestamp, boolean arg4) {
                    updateData((StreamValue)value);                    
                }
            });
            
            viewer.getServer().setPipelineServerListener((cfg)->{
                try{
                    if (cfg.containsKey("image_region_of_interest")){                        
                        int[] roi = (int[]) cfg.get("image_region_of_interest");
                        if (roi==null){
                            dataPipeline.resetRoi();                           
                        } else {
                            dataPipeline.setRoi(roi);
                        }
                        dataPipelineConfig.put("image_region_of_interest", roi);
                    }
                } catch (Exception ex){
                    showException(ex);
                }
            });
                        
            updateDataPause();
            
            if (changed){
                if (hardwarePanel!=null){
                    hardwarePanel.setCamera(cameraName);
                }
            }
            
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
    
    ch.psi.pshell.device.Readable[] getReadables(){
        ch.psi.pshell.device.Readable[] ret = dataPipeline.getStream().getReadables().toArray(new ch.psi.pshell.device.Readable[0]);
        String[] ids = dataPipeline.getStream().getIdentifiers().toArray(new String[0]);
        String[] imageIds = new String[]{"image", "width", "height"};
        if (Arr.containsAllEqual(ids, imageIds)){
            for  (ch.psi.pshell.device.Readable r : Arr.copy(ret)){
                if (Arr.containsEqual(imageIds, r.getName())){
                    ret = Arr.remove(ret, r);
                }
            }
            ret = Arr.append(ret, dataPipeline.getDataMatrix());
        }       
        return ret;
    }
    
    MonitorScan recordingScan;
    
    void startRecording() throws Exception{                
        stopRecording();
        getContext().startExecution(CommandSource.plugin, null, cameraName,null, false);
        getContext().setExecutionPar("name", cameraName);     
        getContext().setExecutionPar("open", true);
        recordingScan=  new MonitorScan(dataPipeline.getStream(), getReadables(), -1, -1);
        Threading.getFuture(() ->recordingScan.start()).handle((ret,t)->{
            recordingScan = null;
            return ret;
        });     
        String fileName = getContext().getExecutionPars().getPath();
        logger.info("Start recording: " + fileName);
        fileHistory.add(fileName);
        listFile.setModel( new javax.swing.AbstractListModel<String>() {
            @Override
            public int getSize() {
                return fileHistory.size();
            }

            @Override
            public String getElementAt(int i) {
                return fileHistory.get(getSize()-i-1);
            }
        });
        listFile.setSelectedIndex(0);
    }
    
    void stopRecording() throws Exception{
        if (recordingScan != null){
            logger.info("Stop recording");        
            recordingScan.abort();        
            getContext().endExecution();
            recordingScan = null;
        }
    }
    
    
    void startSrvRecording() throws Exception{               
        stopSrvRecording();
        String fileName = Context.getInstance().getSetup().expandPath("{date}_{time}_"+cameraName, System.currentTimeMillis());       
        fileName = Paths.get(remoteData, fileName + ".h5").toString();
        logger.info("Start server recording: " + fileName);
        
        HashMap<String, Object> config = (HashMap<String, Object>) ((HashMap)dataPipelineConfig).clone();
        config.put("mode", "FILE");
        config.put("file", fileName);
        config.put("layout", "FLAT");
        config.put("localtime" , false);        
        config.put("change" , false);      
        if (App.hasArgument("rlay")) {
            String[] tokens =  App.getArgumentValue("rlay").split("\\|");
            config.put("layout", tokens[0]);
            if (tokens.length>1){
                config.put("localtime" , Boolean.valueOf(tokens[1]));  
            }
            if (tokens.length>2){
                config.put("change" , Boolean.valueOf(tokens[2]));  
            }
        }                    
        String instanceName = getDataPipeline()+"_save";
        savePipeline = new PipelineServer("Save Pipeline", viewer.getServerUrl()); 
        savePipeline.createFromConfig(config, instanceName);
        savePipeline.start(instanceName, true);       
        
        fileRemHistory.add(fileName);
        listRemFile.setModel( new javax.swing.AbstractListModel<String>() {
            @Override
            public int getSize() {
                return fileRemHistory.size();
            }

            @Override
            public String getElementAt(int i) {
                return fileRemHistory.get(getSize()-i-1);
            }
        });
        listRemFile.setSelectedIndex(0);        
    }
    
    void stopSrvRecording() throws Exception{
        if (savePipeline!=null){
            logger.info("Stop server recording");          
            savePipeline.stopInstance(getDataPipeline()+"_save");
            savePipeline.stop();
            savePipeline = null;
        }
    }
    
    void updateDataPause() throws Exception{
        dataPipeline.setInstanceConfigValue("pause", buttonDataPause.isSelected());
    }    
    

    void openFile(boolean server) throws Exception{
        String filename = server ? listRemFile.getSelectedValue() : listFile.getSelectedValue();
        DataPanel panel = DataPanel.create(new File(filename));
        SwingUtils.showDialog(getTopLevel(), filename, new Dimension(600,400), panel);        
    }
    
    void showPlot(String field) throws Exception{        
        String title = cameraName + " " + field;
        if (deviceDialogs.containsKey(title)){
            JDialog dlg =  deviceDialogs.get(title);
            if ((dlg!=null) && dlg.isShowing()){
                dlg.requestFocus();
                return;
            }
        }
        DeviceListener listener;
        Object obj = dataPipeline.getValue(field);        
        if (field.equals("processing_parameters")) {
            Map<String, Object> pars = viewer.getProcessingParameters(dataPipeline.getStream().take());
            StringBuilder sb = new StringBuilder();
            for (String key : pars.keySet()) {
                sb.append(key).append(" = ").append(Str.toString(pars.get(key), 10)).append("\n");
            }
            SwingUtils.showMessage(this, "Processing Parameters", sb.toString());
        } else if ((obj != null) && (obj.getClass().isArray() || (obj instanceof Number))) {
            DeviceValueChart chart = new DeviceValueChart();
            chart.setAsyncUpdates(true);
                        
            Device dev = null;
            if (obj.getClass().isArray()) {
                dev = new ReadableRegisterArray(new ReadableArray() {
                    @Override
                    public Object read() throws IOException, InterruptedException {
                        return Convert.toDouble(dataPipeline.getValue(field));
                    }

                    @Override
                    public int getSize() {
                        return Array.getLength(dataPipeline.getValue(field));
                    }
                });
            } else {
                dev = new ReadableRegisterNumber(new ReadableNumber() {
                    @Override
                    public Object read() throws IOException, InterruptedException {
                        return Convert.toDouble(dataPipeline.getValue(field));
                    }
                });
            }
            //dev.setPolling(1000);
            Device finalDev = dev;
            chart.setDevice(dev); 
            
            listener = new DeviceAdapter() {
                @Override
                public void onCacheChanged(Device device, Object value, Object former, long timestamp, boolean valueChange) {
                    finalDev.updateAsync();
                }
            };
            dataPipeline.getStream().addListener(listener);
            JDialog dlg = SwingUtils.showDialog(AthosCameras.this.getTopLevel(), title, null, chart);

            deviceDialogs.put(title, dlg);
        }         
    }
    
    void updateButtons(){
        boolean serveRec = savePipeline!=null;
        boolean localRec = recordingScan!=null;
        boolean running = cameraName != null;
        
        buttonReset.setEnabled(running);
        buttonSrvRec.setEnabled(running);
        buttonRec.setEnabled(running);               
        buttonSelect.setEnabled(running);
        buttonPlot.setEnabled((table.getRowCount()>0) && (table.getSelectedRowCount()==1));
        
        buttonOpen.setEnabled(fileHistory.size()>0);        
        buttonSrvOpen.setEnabled((fileRemHistory.size()>0) && 
                                 ((!serveRec) || ((listRemFile.getSelectedIndex()>0)))); 
                
        buttonRec.setSelected(localRec);
        buttonStop.setEnabled(localRec);
        labelRecording.setVisible(localRec);
                    
        buttonSrvRec.setSelected(serveRec);
        buttonSrvStop.setEnabled(serveRec);
        labelSrvRecording.setVisible(serveRec);        
                
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

        viewer = new ch.psi.pshell.bs.StreamCameraViewer();
        jPanel1 = new javax.swing.JPanel();
        panelSrvRec = new javax.swing.JPanel();
        buttonSrvRec = new javax.swing.JToggleButton();
        buttonSrvStop = new javax.swing.JButton();
        buttonSrvOpen = new javax.swing.JButton();
        scrollListRemFile = new javax.swing.JScrollPane();
        listRemFile = new javax.swing.JList<>();
        labelSrvRecording = new javax.swing.JLabel();
        panelData = new javax.swing.JPanel();
        jScrollPane1 = new javax.swing.JScrollPane();
        table = new javax.swing.JTable();
        buttonSelect = new javax.swing.JButton();
        buttonPlot = new javax.swing.JButton();
        buttonDataPause = new javax.swing.JToggleButton();
        buttonReset = new javax.swing.JButton();
        panelRec = new javax.swing.JPanel();
        buttonRec = new javax.swing.JToggleButton();
        buttonStop = new javax.swing.JButton();
        buttonOpen = new javax.swing.JButton();
        scrollListFile = new javax.swing.JScrollPane();
        listFile = new javax.swing.JList<>();
        labelRecording = new javax.swing.JLabel();

        viewer.setLocalFit(java.lang.Boolean.TRUE);
        viewer.setServerUrl("localhost:8889");
        viewer.setShowFit(false);

        panelSrvRec.setBorder(javax.swing.BorderFactory.createTitledBorder("Server Data Recording"));

        buttonSrvRec.setIcon(getIcon("Rec"));
        buttonSrvRec.setToolTipText("Start Data Recording");
        buttonSrvRec.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                buttonSrvRecActionPerformed(evt);
            }
        });

        buttonSrvStop.setIcon(getIcon("Stop"));
        buttonSrvStop.setEnabled(false);
        buttonSrvStop.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                buttonSrvStopActionPerformed(evt);
            }
        });

        buttonSrvOpen.setText("Open");
        buttonSrvOpen.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                buttonSrvOpenActionPerformed(evt);
            }
        });

        scrollListRemFile.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
        scrollListRemFile.setVerticalScrollBarPolicy(javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scrollListRemFile.setSize(new java.awt.Dimension(275, 0));
        scrollListRemFile.setViewportView(null);

        listRemFile.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        listRemFile.addListSelectionListener(new javax.swing.event.ListSelectionListener() {
            public void valueChanged(javax.swing.event.ListSelectionEvent evt) {
                listRemFileValueChanged(evt);
            }
        });
        scrollListRemFile.setViewportView(listRemFile);

        labelSrvRecording.setFont(new java.awt.Font("Lucida Grande", 0, 12)); // NOI18N
        labelSrvRecording.setForeground(new java.awt.Color(255, 0, 0));
        labelSrvRecording.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        labelSrvRecording.setText("RECORDING");

        javax.swing.GroupLayout panelSrvRecLayout = new javax.swing.GroupLayout(panelSrvRec);
        panelSrvRec.setLayout(panelSrvRecLayout);
        panelSrvRecLayout.setHorizontalGroup(
            panelSrvRecLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(panelSrvRecLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(panelSrvRecLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(panelSrvRecLayout.createSequentialGroup()
                        .addComponent(buttonSrvRec)
                        .addGap(0, 0, 0)
                        .addComponent(buttonSrvStop)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 19, Short.MAX_VALUE)
                        .addComponent(labelSrvRecording)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 20, Short.MAX_VALUE)
                        .addComponent(buttonSrvOpen))
                    .addComponent(scrollListRemFile, javax.swing.GroupLayout.DEFAULT_SIZE, 336, Short.MAX_VALUE))
                .addContainerGap())
        );

        panelSrvRecLayout.linkSize(javax.swing.SwingConstants.HORIZONTAL, new java.awt.Component[] {buttonSrvRec, buttonSrvStop});

        panelSrvRecLayout.setVerticalGroup(
            panelSrvRecLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(panelSrvRecLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(panelSrvRecLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.CENTER)
                    .addComponent(buttonSrvRec)
                    .addComponent(buttonSrvStop)
                    .addComponent(labelSrvRecording, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(buttonSrvOpen))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(scrollListRemFile, javax.swing.GroupLayout.PREFERRED_SIZE, 60, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap())
        );

        panelSrvRecLayout.linkSize(javax.swing.SwingConstants.VERTICAL, new java.awt.Component[] {buttonSrvOpen, buttonSrvRec, buttonSrvStop});

        panelData.setBorder(javax.swing.BorderFactory.createTitledBorder("Data Fields"));

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
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseReleased(java.awt.event.MouseEvent evt) {
                tableMouseReleased(evt);
            }
        });
        table.addKeyListener(new java.awt.event.KeyAdapter() {
            public void keyReleased(java.awt.event.KeyEvent evt) {
                tableKeyReleased(evt);
            }
        });
        jScrollPane1.setViewportView(table);

        buttonSelect.setText("Select");
        buttonSelect.setEnabled(false);
        buttonSelect.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                buttonSelectActionPerformed(evt);
            }
        });

        buttonPlot.setText("Plot");
        buttonPlot.setEnabled(false);
        buttonPlot.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                buttonPlotActionPerformed(evt);
            }
        });

        buttonDataPause.setIcon(getIcon("Pause"));
        buttonDataPause.setToolTipText("Start Data Recording");
        buttonDataPause.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                buttonDataPauseActionPerformed(evt);
            }
        });

        buttonReset.setIcon(getIcon("Reset"));
        buttonReset.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                buttonResetActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout panelDataLayout = new javax.swing.GroupLayout(panelData);
        panelData.setLayout(panelDataLayout);
        panelDataLayout.setHorizontalGroup(
            panelDataLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, panelDataLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(panelDataLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 0, Short.MAX_VALUE)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, panelDataLayout.createSequentialGroup()
                        .addComponent(buttonReset)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(buttonDataPause)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 12, Short.MAX_VALUE)
                        .addComponent(buttonSelect)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(buttonPlot)))
                .addContainerGap())
        );

        panelDataLayout.linkSize(javax.swing.SwingConstants.HORIZONTAL, new java.awt.Component[] {buttonPlot, buttonSelect});

        panelDataLayout.linkSize(javax.swing.SwingConstants.HORIZONTAL, new java.awt.Component[] {buttonDataPause, buttonReset});

        panelDataLayout.setVerticalGroup(
            panelDataLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(panelDataLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(panelDataLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.CENTER)
                    .addComponent(buttonDataPause)
                    .addComponent(buttonPlot)
                    .addComponent(buttonSelect)
                    .addComponent(buttonReset))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 0, Short.MAX_VALUE)
                .addContainerGap())
        );

        panelDataLayout.linkSize(javax.swing.SwingConstants.VERTICAL, new java.awt.Component[] {buttonDataPause, buttonPlot, buttonReset, buttonSelect});

        panelRec.setBorder(javax.swing.BorderFactory.createTitledBorder("Local Data Recording"));

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

        buttonOpen.setText("Open");
        buttonOpen.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                buttonOpenActionPerformed(evt);
            }
        });

        scrollListFile.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
        scrollListFile.setVerticalScrollBarPolicy(javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scrollListFile.setViewportView(null);

        listFile.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        listFile.setSize(new java.awt.Dimension(275, 0));
        scrollListFile.setViewportView(listFile);

        labelRecording.setFont(new java.awt.Font("Lucida Grande", 0, 12)); // NOI18N
        labelRecording.setForeground(new java.awt.Color(255, 0, 0));
        labelRecording.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        labelRecording.setText("RECORDING");

        javax.swing.GroupLayout panelRecLayout = new javax.swing.GroupLayout(panelRec);
        panelRec.setLayout(panelRecLayout);
        panelRecLayout.setHorizontalGroup(
            panelRecLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(panelRecLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(panelRecLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(panelRecLayout.createSequentialGroup()
                        .addComponent(scrollListFile, javax.swing.GroupLayout.DEFAULT_SIZE, 336, Short.MAX_VALUE)
                        .addContainerGap())
                    .addGroup(panelRecLayout.createSequentialGroup()
                        .addComponent(buttonRec)
                        .addGap(0, 0, 0)
                        .addComponent(buttonStop)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 22, Short.MAX_VALUE)
                        .addComponent(labelRecording)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 23, Short.MAX_VALUE)
                        .addComponent(buttonOpen))))
        );

        panelRecLayout.linkSize(javax.swing.SwingConstants.HORIZONTAL, new java.awt.Component[] {buttonRec, buttonStop});

        panelRecLayout.setVerticalGroup(
            panelRecLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(panelRecLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(panelRecLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.CENTER)
                    .addComponent(buttonRec)
                    .addComponent(buttonStop)
                    .addComponent(buttonOpen)
                    .addComponent(labelRecording, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(scrollListFile, javax.swing.GroupLayout.PREFERRED_SIZE, 60, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap())
        );

        panelRecLayout.linkSize(javax.swing.SwingConstants.VERTICAL, new java.awt.Component[] {buttonOpen, buttonRec, buttonStop});

        javax.swing.GroupLayout jPanel1Layout = new javax.swing.GroupLayout(jPanel1);
        jPanel1.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addGap(0, 0, 0)
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addComponent(panelRec, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addComponent(panelData, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addComponent(panelSrvRec, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addContainerGap())
        );

        jPanel1Layout.linkSize(javax.swing.SwingConstants.HORIZONTAL, new java.awt.Component[] {panelData, panelRec, panelSrvRec});

        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(panelData, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(panelSrvRec, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(panelRec, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap())
        );

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addComponent(viewer, javax.swing.GroupLayout.DEFAULT_SIZE, 411, Short.MAX_VALUE)
                .addGap(0, 0, 0)
                .addComponent(jPanel1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(0, 0, 0))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(viewer, javax.swing.GroupLayout.DEFAULT_SIZE, 603, Short.MAX_VALUE)
            .addComponent(jPanel1, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );
    }// </editor-fold>//GEN-END:initComponents

    private void buttonRecActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_buttonRecActionPerformed
        try{
            if (buttonRec.isSelected()){
                startRecording();
            } else {
                stopRecording();
            }
        } catch (Exception ex){
            this.showException(ex);
        }  
        updateButtons();
    }//GEN-LAST:event_buttonRecActionPerformed

    private void buttonStopActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_buttonStopActionPerformed
        buttonRec.setSelected(false);
        buttonRecActionPerformed(null);
    }//GEN-LAST:event_buttonStopActionPerformed

    private void buttonOpenActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_buttonOpenActionPerformed
        try{
            openFile(false);
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
                dlg.set(viewer.getServerUrl(), instance);
                dlg.setVisible(true);
                if (dlg.getResult()){
                    setDataFields(dlg.selected);
                }
            }
        } catch (Exception ex){
            this.showException(ex);
        }
    }//GEN-LAST:event_buttonSelectActionPerformed

    private void buttonPlotActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_buttonPlotActionPerformed
        try{
            if (table.getSelectedRow()>=0){
                String field = (String) model.getValueAt(table.getSelectedRow(), 0);
                showPlot(field);
            }
        } catch (Exception ex){
            this.showException(ex);
        }
    }//GEN-LAST:event_buttonPlotActionPerformed

    private void tableMouseReleased(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_tableMouseReleased
        updateButtons();
    }//GEN-LAST:event_tableMouseReleased

    private void tableKeyReleased(java.awt.event.KeyEvent evt) {//GEN-FIRST:event_tableKeyReleased
        updateButtons();
    }//GEN-LAST:event_tableKeyReleased

    private void buttonSrvRecActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_buttonSrvRecActionPerformed
        try{
            if (buttonSrvRec.isSelected()){
                startSrvRecording();
            } else {
                stopSrvRecording();
            }
        } catch (Exception ex){
            this.showException(ex);
        }
        updateButtons();
    }//GEN-LAST:event_buttonSrvRecActionPerformed

    private void buttonSrvStopActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_buttonSrvStopActionPerformed
        buttonSrvRec.setSelected(false);
        buttonSrvRecActionPerformed(null);
    }//GEN-LAST:event_buttonSrvStopActionPerformed

    private void buttonDataPauseActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_buttonDataPauseActionPerformed
        try{
            updateDataPause();
        } catch (Exception ex){
            buttonDataPause.setSelected(false);
            this.showException(ex);
        }
    }//GEN-LAST:event_buttonDataPauseActionPerformed

    private void buttonSrvOpenActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_buttonSrvOpenActionPerformed
        try{
            openFile(true);
        } catch (Exception ex){
            this.showException(ex);
        }
    }//GEN-LAST:event_buttonSrvOpenActionPerformed

    private void buttonResetActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_buttonResetActionPerformed
        try{
            setCamera(cameraName);
        } catch (Exception ex){
            this.showException(ex);
        }
    }//GEN-LAST:event_buttonResetActionPerformed

    private void listRemFileValueChanged(javax.swing.event.ListSelectionEvent evt) {//GEN-FIRST:event_listRemFileValueChanged
        updateButtons();
    }//GEN-LAST:event_listRemFileValueChanged

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JToggleButton buttonDataPause;
    private javax.swing.JButton buttonOpen;
    private javax.swing.JButton buttonPlot;
    private javax.swing.JToggleButton buttonRec;
    private javax.swing.JButton buttonReset;
    private javax.swing.JButton buttonSelect;
    private javax.swing.JButton buttonSrvOpen;
    private javax.swing.JToggleButton buttonSrvRec;
    private javax.swing.JButton buttonSrvStop;
    private javax.swing.JButton buttonStop;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JLabel labelRecording;
    private javax.swing.JLabel labelSrvRecording;
    private javax.swing.JList<String> listFile;
    private javax.swing.JList<String> listRemFile;
    private javax.swing.JPanel panelData;
    private javax.swing.JPanel panelRec;
    private javax.swing.JPanel panelSrvRec;
    private javax.swing.JScrollPane scrollListFile;
    private javax.swing.JScrollPane scrollListRemFile;
    private javax.swing.JTable table;
    private ch.psi.pshell.bs.StreamCameraViewer viewer;
    // End of variables declaration//GEN-END:variables
}
