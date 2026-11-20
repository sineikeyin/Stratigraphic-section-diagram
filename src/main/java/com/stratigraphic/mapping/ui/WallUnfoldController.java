package com.stratigraphic.mapping.ui;

import com.stratigraphic.mapping.chart.ResponseChart;
import com.stratigraphic.mapping.chart.ResponseViewport;
import com.stratigraphic.mapping.simulation.*;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.*;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.util.Locale;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.BiFunction;

/** Second-page state is independent of the first page and of selected historical results. */
public final class WallUnfoldController {
    @FXML private BorderPane appRoot;
    @FXML private VBox parameterSlot;
    @FXML private StackPane amplitudeBox,phaseBox;
    @FXML private Label statusMessage;
    @FXML private Button exportButton,resetButton;
    private final Button loadButton=new Button("获得输入数据"),computeButton=new Button("计算输出数据"),plotButton=new Button("生成图片");
    private final ProgressBar[] progress={new ProgressBar(0),new ProgressBar(0),new ProgressBar(0)};
    private final Label[] messages={label("等待加载输入数据"),label("等待输入数据就绪"),label("等待生成图片")};
    private final Label resultInfo=label("暂无计算结果");
    private final Button stopButton=new Button("停止计算");
    private final Button pauseButton=new Button("暂停计算"),resumeBatchButton=new Button("继续所选计算");
    private final ListView<SimulationHistory.Entry> unfinishedBatches=new ListView<>();
    private final TitledPane historyPane=new TitledPane("历史记录",new VBox(8,unfinishedBatches,resumeBatchButton));
    private List<SimulationHistory.Entry> history=List.of();
    private int historyRevision;
    private ParallelSimulationRunner.Control calculationControl;
    private final Path workspaceRoot;
    private Path computedResult;
    private Path resultDirectory=SimulationRunner.DEFAULT_ROOT.resolve("output");
    // Allows integration tests to exercise dialog choices without opening a native modal window.
    BiFunction<FileChooser,Window,File> resultFilePicker=FileChooser::showOpenDialog;
    private final Spinner<Integer> parallelism=new Spinner<>(1,ParallelSimulationRunner.maxParallelism(),ParallelSimulationRunner.defaultParallelism());
    private final HBox parallelismSettings=new HBox(8,label("同时计算通道数"),parallelism);
    private record Computed(SimulationRunner.Outcome outcome,ResponseData data) { }
    private final TextArea errorDetails=new TextArea();
    private final TitledPane errorPane=new TitledPane("错误详情",errorDetails);
    private final TextField amplitudeMin=new TextField(),amplitudeMax=new TextField(),phaseMin=new TextField(),phaseMax=new TextField();
    private final Button applyColors=new Button("应用色标");
    private final ResponseViewport amplitudeViewport=new ResponseViewport(),phaseViewport=new ResponseViewport();
    private ModelInputs inputs;
    private ResponseData result;
    private ResponseChart amplitudeChart,phaseChart;
    private boolean busy;
    private Path inputDirectory=SimulationRunner.DEFAULT_ROOT.resolve("192_NGI_Input");
    public WallUnfoldController() {this(SimulationRunner.DEFAULT_ROOT);}
    WallUnfoldController(Path workspaceRoot) {
        this.workspaceRoot=workspaceRoot;
        inputDirectory=workspaceRoot.resolve("192_NGI_Input");resultDirectory=workspaceRoot.resolve("output");
    }
    @FXML private void initialize() {
        computeButton.setDisable(true); applyColors.setDisable(true);statusMessage.getStyleClass().add("msg");
        VBox form=new VBox(10);form.setPadding(new Insets(8));form.getStyleClass().addAll("parameter-pane","imaging-parameters");
        VBox[] sections={new VBox(5),new VBox(5),new VBox(5)};
        for(int index=0;index<sections.length;index++) sections[index].setId("imagingStep"+(index+1));
        form.getChildren().addAll(sections[0],new Separator(),sections[1],new Separator(),sections[2]);
        Button[] buttons={loadButton,computeButton,plotButton};
        stopButton.setVisible(false);stopButton.managedProperty().bind(stopButton.visibleProperty());
        stopButton.setOnAction(e->{if(calculationControl!=null)calculationControl.stop();stopButton.setDisable(true);pauseButton.setDisable(true);});
        pauseButton.setOnAction(e->{
            if(calculationControl==null)return;
            if(calculationControl.isPauseRequested()) {calculationControl.resume();pauseButton.setText("暂停计算");}
            else {calculationControl.pause();pauseButton.setText("继续计算");statusMessage.setText("正在暂停；已完成电极保留，未完成电极继续时重算");}
        });
        HBox calculationButtons=new HBox(8,pauseButton,stopButton);
        calculationButtons.visibleProperty().bind(stopButton.visibleProperty());calculationButtons.managedProperty().bind(calculationButtons.visibleProperty());
        parallelism.setId("parallelism");parallelism.setPrefWidth(85);
        parallelismSettings.setId("parallelismSettings");
        parallelism.setTooltip(new Tooltip("默认使用本机可用逻辑处理器数；运行期间保持显示，结束后可调整"));
        for(int i=0;i<3;i++) {
            progress[i].setId(new String[]{"inputProgress","calculationProgress","resultProgress"}[i]);
            buttons[i].getStyleClass().add("ghost-button");buttons[i].setMaxWidth(Double.MAX_VALUE);progress[i].setMaxWidth(Double.MAX_VALUE);
            sections[i].getChildren().addAll(buttons[i],progress[i],messages[i]);
            if(i==1)sections[i].getChildren().addAll(parallelismSettings,calculationButtons);
        }
        unfinishedBatches.setId("unfinishedBatches");unfinishedBatches.setPrefHeight(138);
        unfinishedBatches.setPlaceholder(label("暂无历史记录"));
        unfinishedBatches.setCellFactory(view->new ListCell<>() {
            @Override protected void updateItem(SimulationHistory.Entry entry,boolean empty) {
                super.updateItem(entry,empty);setText(empty||entry==null?null:entry.toString());
                setTooltip(empty||entry==null?null:new Tooltip(entry.batch().toString()));
            }
        });
        resumeBatchButton.setMaxWidth(Double.MAX_VALUE);resumeBatchButton.setDisable(true);
        resumeBatchButton.setOnAction(e->{var entry=unfinishedBatches.getSelectionModel().getSelectedItem();if(entry!=null&&entry.resumable()&&!busy)startComputation(entry.batch());});
        unfinishedBatches.getSelectionModel().selectedItemProperty().addListener((observable,previous,current)->controls());
        sections[1].getChildren().add(historyPane);
        GridPane colors=new GridPane();colors.setHgap(6);colors.setVgap(6);
        colors.getColumnConstraints().addAll(new ColumnConstraints(),new ColumnConstraints());
        TextField[] fields={amplitudeMin,amplitudeMax,phaseMin,phaseMax};
        String[] names={"模值下限 Ω·m（>0）","模值上限 Ω·m","相位下限 °","相位上限 °"};
        for(int i=0;i<4;i++) {fields[i].setPrefColumnCount(7);fields[i].setMinWidth(0);colors.add(label(names[i]),0,i);colors.add(fields[i],1,i);}
        TitledPane colorPane=new TitledPane("色标设置",new VBox(6,colors,applyColors));colorPane.setExpanded(false);
        TitledPane resultPane=new TitledPane("有关计算结果信息",new VBox(6,resultInfo,colorPane));
        resultPane.setExpanded(true);sections[2].getChildren().add(resultPane);
        errorDetails.setEditable(false);errorDetails.setWrapText(true);errorDetails.setPrefRowCount(7);
        errorPane.setExpanded(false);errorPane.setVisible(false);errorPane.managedProperty().bind(errorPane.visibleProperty());form.getChildren().add(errorPane);
        ScrollPane scroll=new ScrollPane(form);scroll.setFitToWidth(true);scroll.setMinWidth(0);VBox.setVgrow(scroll,Priority.ALWAYS);parameterSlot.getChildren().setAll(scroll);
        loadButton.setOnAction(e->loadInputs());computeButton.setOnAction(e->compute());plotButton.setOnAction(e->selectResult());applyColors.setOnAction(e->applyColors());
        MenuItem chooseInput=new MenuItem("选择输入目录…");chooseInput.setOnAction(e->{if(!busy)chooseInput();});
        loadButton.setContextMenu(new ContextMenu(chooseInput));
        refreshHistory();
    }
    private static Label label(String text) {Label l=new Label(text);l.setWrapText(true);l.setMinWidth(0);return l;}
    private Window window() {return appRoot.getScene()==null?null:appRoot.getScene().getWindow();}
    private void chooseInput() {
        DirectoryChooser chooser=new DirectoryChooser();chooser.setTitle("选择包含 Card_001～Card_192 的输入目录");
        if(Files.isDirectory(inputDirectory)) chooser.setInitialDirectory(inputDirectory.toFile());
        File selected=chooser.showDialog(window());if(selected!=null) {inputDirectory=selected.toPath();loadInputs();}
    }
    private void loadInputs() {
        if(busy) return;
        boolean convertedFromSlices=inputDirectory.toAbsolutePath().normalize().equals(workspaceRoot.resolve("192_NGI_Input").toAbsolutePath().normalize());
        if(!convertedFromSlices&&!Files.isDirectory(inputDirectory)) {error("输入目录不可用："+inputDirectory+"。请重新选择目录。");chooseInput();return;}
        inputs=null;computeButton.setDisable(true);
        Task<ModelInputs> task=new Task<>() {
            @Override protected ModelInputs call() throws Exception {
                Path cache=Path.of(System.getProperty("java.io.tmpdir"),"stratigraphic-input-cache");
                if(convertedFromSlices) {
                    updateMessage("正在从原始切片转换并校验输入…");
                    var prepared=InputPreparation.regenerate(workspaceRoot,cache,(done,message)->{updateProgress(done,384);updateMessage(message);});
                    return prepared.inputs();
                }
                return ModelInputs.load(inputDirectory,cache,(n,s)->{updateProgress(n,192);updateMessage(s);});
            }
        };
        runTask(task,0,value->{inputs=value;messages[0].setText(convertedFromSlices?"已转换并校验 192/192，输入数据就绪":"已加载并校验 192/192，输入数据就绪");});
    }
    private void compute() {
        startComputation(null);
    }
    private void startComputation(Path batch) {
        if(busy||(batch==null&&inputs==null))return;
        ModelInputs snapshot=inputs;
        int concurrentChannels=parallelism.getValue();
        var control=new ParallelSimulationRunner.Control();calculationControl=control;
        stopButton.setDisable(false);stopButton.setVisible(true);pauseButton.setText("暂停计算");pauseButton.setDisable(false);
        Task<Computed> task=new Task<>() {
            @Override protected Computed call() throws Exception {
                updateMessage(batch==null?"正在准备电极计算…":"正在校验并恢复已保存电极…");
                Consumer<ParallelSimulationRunner.Progress> display=p->{
                    double fraction=p.fraction();
                    updateProgress(fraction==0&&!p.paused()?ProgressIndicator.INDETERMINATE_PROGRESS:fraction,1);updateMessage(p.message());
                };
                var runner=new ParallelSimulationRunner();
                var outcome=batch==null?runner.run(snapshot,workspaceRoot,workspaceRoot.resolve("output"),192,concurrentChannels,display,control):
                    runner.resume(batch,workspaceRoot,concurrentChannels,display,control);
                return new Computed(outcome,ResponseData.read(outcome.result(),(a,b)->{}));
            }
        };
        runTask(task,1,value->{acceptComputedResult(value.data());messages[1].setText("已完成 192/192，结果已保存\n"+computedResult);messages[2].setText("结果已保存，可生成图片");statusMessage.setText("完整结果已保存；点击「生成图片」选择结果文件绘图");});
    }
    private void refreshHistory() {
        int revision=++historyRevision;
        Task<List<SimulationHistory.Entry>> scan=new Task<>() {
            @Override protected List<SimulationHistory.Entry> call()throws IOException {return SimulationHistory.all(workspaceRoot.resolve("output"));}
        };
        scan.setOnSucceeded(event->{
            if(revision!=historyRevision)return;
            history=scan.getValue();var selected=unfinishedBatches.getSelectionModel().getSelectedItem();unfinishedBatches.getItems().setAll(history);
            var retained=selected==null?null:history.stream().filter(entry->entry.batch().equals(selected.batch())).findFirst().orElse(null);
            if(retained!=null)unfinishedBatches.getSelectionModel().select(retained);else unfinishedBatches.getSelectionModel().selectFirst();
            controls();
        });
        scan.setOnFailed(event->{if(revision==historyRevision)statusMessage.setText("历史记录读取失败："+scan.getException().getMessage());});
        Thread thread=new Thread(scan,"simulation-history");thread.setDaemon(true);thread.start();
    }
    void acceptComputedResult(ResponseData data) {
        computedResult=data.source();resultDirectory=computedResult.toAbsolutePath().getParent();resultInfo.setText(data.resultSummary());
        if(amplitudeChart!=null)amplitudeChart.dispose();if(phaseChart!=null)phaseChart.dispose();
        amplitudeChart=null;phaseChart=null;result=null;
        amplitudeBox.getChildren().clear();phaseBox.getChildren().clear();
        exportButton.setDisable(true);resetButton.setDisable(true);applyColors.setDisable(true);
    }
    private void selectResult() {
        if(busy) return;
        FileChooser chooser=new FileChooser();chooser.setTitle("选择计算结果文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("计算结果 TXT","*.txt"));
        if(Files.isDirectory(resultDirectory))chooser.setInitialDirectory(resultDirectory.toFile());
        File file=resultFilePicker.apply(chooser,window());if(file==null)return;
        Path selectedResult=file.toPath();
        Task<ResponseData> task=new Task<>() {
            @Override protected ResponseData call() throws Exception {
                updateMessage("读取并校验："+selectedResult.getFileName());
                return ResponseData.read(selectedResult,(done,total)->{updateProgress(done,total);updateMessage("已读取 "+done+" / "+total+" 字节，校验深度与通道");});
            }
        };
        runTask(task,2,this::showResult);
    }
    // Package-visible for integration tests with real validated files.
    void showResult(ResponseData value) {
        resultDirectory=value.source().toAbsolutePath().getParent();
        double amin=value.minPositive(),amax=value.max(false);
        if(!Double.isFinite(amin)) {amin=1;amax=10;}
        if(amax<=amin) {amin/=Math.sqrt(10);amax*=Math.sqrt(10);}
        double pmin=value.min(true),pmax=value.max(true);if(pmax<=pmin) {pmin-=1;pmax+=1;}
        if(amplitudeChart!=null) amplitudeChart.dispose();if(phaseChart!=null) phaseChart.dispose();
        amplitudeViewport.reset();phaseViewport.reset();
        ResponseChart a=new ResponseChart(value,false,amin,amax,amplitudeViewport);
        ResponseChart p=new ResponseChart(value,true,pmin,pmax,phaseViewport);
        result=value;amplitudeChart=a;phaseChart=p;
        amplitudeBox.getChildren().setAll(a);phaseBox.getChildren().setAll(p);
        amplitudeMin.setText(""+amin);amplitudeMax.setText(""+amax);phaseMin.setText(""+pmin);phaseMax.setText(""+pmax);
        resultInfo.setText(value.resultSummary());messages[2].setText("已生成本文件全部 192 个电极的图片 · "+(long)value.size()*192+" 条记录");
        if(!progress[2].progressProperty().isBound()) progress[2].setProgress(1);
        statusMessage.setText("文件来自："+value.source());
        statusMessage.setTooltip(new Tooltip(statusMessage.getText()));
        exportButton.setDisable(false);resetButton.setDisable(false);applyColors.setDisable(false);
    }
    private void applyColors() {
        if(result==null) return;
        try {
            double a=Double.parseDouble(amplitudeMin.getText()),b=Double.parseDouble(amplitudeMax.getText()),c=Double.parseDouble(phaseMin.getText()),d=Double.parseDouble(phaseMax.getText());
            if(!Double.isFinite(a)||!Double.isFinite(b)||!Double.isFinite(c)||!Double.isFinite(d)||a<=0||b<=a||d<=c) throw new IllegalArgumentException("色标需要有限数值，模值下限 >0，且各上限大于下限");
            amplitudeChart.setRange(a,b);phaseChart.setRange(c,d);
        } catch(IllegalArgumentException ex) {error("色标错误："+ex.getMessage());}
    }
    private <T> void runTask(Task<T> task,int index,Consumer<T> success) {
        busy=true;controls();progress[index].progressProperty().bind(task.progressProperty());messages[index].textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e->{unbind(index);progress[index].setProgress(1);busy=false;finishCompute(index);try {success.accept(task.getValue());} catch(RuntimeException ex){error(ex.getMessage());}controls();});
        task.setOnFailed(e->{
            unbind(index);if(progress[index].getProgress()<0)progress[index].setProgress(0);busy=false;finishCompute(index);
            if(index==1&&task.getException() instanceof SimulationRunner.StoppedException) {
                messages[index].setText("计算已停止，已完成电极已保存，可从历史记录选择继续");statusMessage.setText(task.getException().getMessage());
            } else {messages[index].setText(firstLine(task.getException().getMessage())+"（见错误详情）");error(task.getException().getMessage());}
            controls();
        });
        Thread thread=new Thread(task,"imaging-task");thread.setDaemon(true);thread.start();
    }
    private void unbind(int index) {progress[index].progressProperty().unbind();messages[index].textProperty().unbind();}
    private void finishCompute(int index) {if(index==1){stopButton.setVisible(false);calculationControl=null;refreshHistory();}}
    private void controls() {
        loadButton.setDisable(busy);computeButton.setDisable(busy||inputs==null);plotButton.setDisable(busy);parallelism.setDisable(busy);applyColors.setDisable(busy||result==null);
        historyPane.setDisable(busy);
        var selected=unfinishedBatches.getSelectionModel().getSelectedItem();
        resumeBatchButton.setDisable(busy||selected==null||!selected.resumable());
    }
    private static String firstLine(String message) {return message==null?"未知错误":message.lines().findFirst().orElse("未知错误");}
    private void error(String message) {statusMessage.setText(firstLine(message)+" · 详细信息见左侧「错误详情」");errorDetails.setText(message);errorPane.setVisible(true);}
    @FXML private void resetView() {if(result!=null) {amplitudeViewport.reset();phaseViewport.reset();}}
    @FXML private void exportPng() {
        if(result==null) return;
        FileChooser chooser=new FileChooser();chooser.setTitle("导出当前结果页面 PNG（含待接入说明）");chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG","*.png"));
        File file=chooser.showSaveDialog(window());if(file==null) return;
        if(!file.getName().toLowerCase(Locale.ROOT).endsWith(".png")) file=new File(file+".png");
        try {SnapshotParameters p=new SnapshotParameters();p.setFill(Color.WHITE);ImageIO.write(SwingFXUtils.fromFXImage(appRoot.snapshot(p,null),null),"png",file);statusMessage.setText("已导出："+file);}
        catch(IOException ex) {error("导出失败："+ex.getMessage());}
    }
    @FXML private void showMainView() {
        try {PageNavigation.show(appRoot,"wall-unfold-view.fxml","main-view.fxml","地层剖面与井壁展开图");}
        catch(IOException|IllegalStateException ex) {error(ex.getMessage());}
    }
}
