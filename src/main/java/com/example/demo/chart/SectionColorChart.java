package com.example.demo.chart;

import com.example.demo.model.StratumInterface;
import com.example.demo.model.StratumModel;
import javafx.geometry.Point2D;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;

import java.util.ArrayList;
import java.util.List;

/**
 * 完整的地层剖面图：地层色块 + 界面线 + 井筒 + 电阻率色标 + 边框刻度。
 *
 * <p>颜色表示电阻率：浅（低阻）→ 深（高阻），色标轴画在图面右侧。
 * 点击图面任意位置会弹出该处的深度、界面夹角与电阻率。
 */
public final class SectionColorChart extends Pane {

    private static final double TITLE_H = 34;
    private static final double MARGIN_LEFT = 46;
    private static final double MARGIN_RIGHT = 26;
    private static final double MARGIN_BOTTOM = 46;

    /** 图面右边缘 → 色标带。 */
    private static final double CB_GAP = 34;
    /** 色标带宽度。 */
    private static final double CB_W = 22;
    /** 色标刻度数字占宽。 */
    private static final double CB_LABEL_W = 44;

    /** 色标等分几段。 */
    private static final int CB_STEPS = 5;

    private static final double INFO_W = 168;
    private static final double INFO_H = 72;

    private final StratumModel model;
    private final ColorScale scale;
    private final SectionGeometry geo;

    private Pane infoBox;
    private Label infoText;

    public SectionColorChart(StratumModel model) {

        this.model = model;
        this.scale = new ColorScale(model.maxResistivity());
        this.geo = new SectionGeometry(model, MARGIN_LEFT, TITLE_H);

        setPrefSize(
                MARGIN_LEFT + geo.figureWidth() + CB_GAP + CB_W + CB_LABEL_W + MARGIN_RIGHT,
                TITLE_H + geo.figureHeight() + MARGIN_BOTTOM
        );
        getStyleClass().add("chart-canvas");

        // 按工程图顺序绘制，后画的盖住先画的
        drawLayers();
        drawInterfaces();
        drawWell();
        drawDepthLabels();
        drawColorBar();
        drawFrame();
        drawAxes();

        createInfoBox();
        setOnMouseClicked(this::handleClick);
    }

    // ---------------- 地层色块 ----------------

    private void drawLayers() {

        double[] cuts = geo.layerCuts();

        Shape[] shapes = new Shape[model.layerCount()];

        for (int i = 0; i < cuts.length - 1; i++) {

            double xl = cuts[i];
            double xr = cuts[i + 1];
            double xm = (xl + xr) / 2;          // 层序在这一竖条的中点取样

            int[] order = geo.orderAt(xm);

            for (int layer = 0; layer < model.layerCount(); layer++) {

                Polygon piece = slabPolygon(xl, xr, order, layer);

                if (piece == null) {
                    continue;                    // 这一竖条内该层尖灭了
                }

                shapes[layer] = shapes[layer] == null
                        ? piece
                        : Shape.union(shapes[layer], piece);
            }
        }

        for (int layer = 0; layer < model.layerCount(); layer++) {

            if (shapes[layer] == null) {
                continue;
            }

            shapes[layer].setFill(scale.colorOf(model.resistivityOf(layer)));
            getChildren().add(shapes[layer]);
        }
    }

    /** 竖条 [xl, xr] 内第 layer 层的多边形；尖灭时返回 null。 */
    private Polygon slabPolygon(double xl, double xr, int[] order, int layer) {

        List<Point2D> poly = new ArrayList<>();
        poly.add(new Point2D(xl, geo.figureTop()));
        poly.add(new Point2D(xr, geo.figureTop()));
        poly.add(new Point2D(xr, geo.figureBottom()));
        poly.add(new Point2D(xl, geo.figureBottom()));

        List<StratumInterface> interfaces = model.interfaces();

        // 层的上界：上面那条界面，保留更深的一侧
        if (layer > 0) {
            poly = clipHalfPlane(poly, interfaces.get(order[layer - 1]), true);
        }

        // 层的下界：下面那条界面，保留更浅的一侧
        if (layer < interfaces.size()) {
            poly = clipHalfPlane(poly, interfaces.get(order[layer]), false);
        }

        return poly.size() < 3 ? null : new Polygon(SectionGeometry.flatten(poly));
    }

    /** 用一条界面把多边形裁成两半，保留 keepDeeper 指定的一侧。 */
    private List<Point2D> clipHalfPlane(List<Point2D> poly, StratumInterface s, boolean keepDeeper) {

        double px = geo.axisX();
        double py = geo.zToY(s.depth());
        double nx = SectionGeometry.normalX(s.angle());
        double ny = SectionGeometry.normalY(s.angle());

        List<Point2D> out = new ArrayList<>();
        int n = poly.size();

        for (int i = 0; i < n; i++) {

            Point2D cur = poly.get(i);
            Point2D nxt = poly.get((i + 1) % n);

            double dc = (cur.getX() - px) * nx + (cur.getY() - py) * ny;
            double dn = (nxt.getX() - px) * nx + (nxt.getY() - py) * ny;

            boolean curIn = keepDeeper ? dc >= 0 : dc <= 0;
            boolean nxtIn = keepDeeper ? dn >= 0 : dn <= 0;

            if (curIn) {
                out.add(cur);
            }
            if (curIn != nxtIn) {
                double t = dc / (dc - dn);
                out.add(new Point2D(
                        cur.getX() + t * (nxt.getX() - cur.getX()),
                        cur.getY() + t * (nxt.getY() - cur.getY())));
            }
        }
        return out;
    }

    // ---------------- 界面线 ----------------

    private void drawInterfaces() {

        List<StratumInterface> interfaces = model.interfaces();

        for (int i = 0; i < interfaces.size(); i++) {

            StratumInterface s = interfaces.get(i);

            // 先铺一条半透明白晕，界面线压在深色地层上也看得清
            Line glow = geo.createLine(geo.axisX(), geo.zToY(s.depth()), s.angle());
            glow.setStroke(Color.web("#FFFFFF", 0.5));
            glow.setStrokeWidth(2.4);
            getChildren().add(glow);

            Line line = geo.createLine(geo.axisX(), geo.zToY(s.depth()), s.angle());
            line.setStroke(SectionStyle.INTERFACE_LINE);
            line.setStrokeWidth(1.2);

            getChildren().add(line);
        }
    }

    // ---------------- 井筒 ----------------

    private void drawWell() {

        Rectangle well = new Rectangle(
                geo.axisX() - geo.wellRadiusPx(),
                geo.figureTop(),
                2 * geo.wellRadiusPx(),
                geo.figureHeight());

        well.setFill(scale.colorOf(model.wellResistivity()));

        getChildren().add(well);
    }

    // ---------------- 井轴处的深度标注 ----------------

    private void drawDepthLabels() {

        double x = geo.axisX() + geo.wellRadiusPx() + 8;

        for (StratumInterface s : model.interfaces()) {
            getChildren().add(
                    label(String.format("%.2f m", s.depth()),
                            x, geo.zToY(s.depth()) - 19, SectionStyle.chip(11)));
        }
    }

    // ---------------- 图面边框 ----------------

    private void drawFrame() {

        Rectangle frame = new Rectangle(
                geo.figureLeft(), geo.figureTop(),
                geo.figureWidth(), geo.figureHeight());

        frame.setFill(null);
        frame.setStroke(SectionStyle.FRAME);
        frame.setStrokeWidth(1.5);

        getChildren().add(frame);
    }

    // ---------------- 电阻率色标 ----------------

    private void drawColorBar() {

        double cbX = geo.figureRight() + CB_GAP;
        double top = geo.figureTop();
        double bottom = geo.figureBottom();

        Stop[] stops = new Stop[11];
        for (int i = 0; i <= 10; i++) {
            // i = 0 在最下面（低阻、浅色），i = 10 在最上面（高阻、深色）
            stops[10 - i] = new Stop(1 - i / 10.0, scale.colorOf(scale.max() * i / 10.0));
        }

        Rectangle bar = new Rectangle(cbX, top, CB_W, bottom - top);
        bar.setFill(new LinearGradient(0, 0, 0, 1, true,
                CycleMethod.NO_CYCLE, stops));
        bar.setStroke(SectionStyle.COLOR_BAR_BORDER);
        bar.setStrokeWidth(0.8);

        getChildren().add(bar);

        double step = scale.max() / CB_STEPS;
        int decimals = SectionStyle.decimalsFor(step);

        for (int k = 0; k <= CB_STEPS; k++) {

            double rho = step * k;
            double y = rhoToY(rho);

            Line tick = new Line(cbX + CB_W, y, cbX + CB_W + 5, y);
            tick.setStroke(SectionStyle.TICK);
            tick.setStrokeWidth(0.8);
            getChildren().add(tick);

            getChildren().add(
                    label(SectionStyle.formatTick(rho, decimals),
                            cbX + CB_W + 9, y - 8, SectionStyle.muted(10)));
        }

        getChildren().add(
                label("电阻率", cbX - 6, geo.figureTop() - 26, SectionStyle.bold(12)));
    }

    /** 电阻率 → 色标上的 y 坐标（0 在最下面，上界在最上面）。 */
    private double rhoToY(double rho) {
        return geo.figureBottom() - rho / scale.max() * geo.figureHeight();
    }

    // ---------------- 标题 ----------------

    private void drawTitle() {

        getChildren().add(
                label("地层剖面图", geo.axisX() - 45, 14, SectionStyle.title(18)));
    }

    // ---------------- 边框刻度 ----------------

    private void drawAxes() {

        drawDepthTicks();
        drawOffsetTicks();
    }

    private void drawDepthTicks() {

        double step = SectionStyle.niceStep((geo.zBottom() - geo.zTop()) / 10);
        int decimals = SectionStyle.decimalsFor(step);

        int first = (int) Math.ceil(geo.zTop() / step - 1e-9);
        int last = (int) Math.floor(geo.zBottom() / step + 1e-9);

        for (int k = first; k <= last; k++) {

            double z = k * step;
            double y = geo.zToY(z);

            Line tick = new Line(geo.figureLeft() - 6, y, geo.figureLeft(), y);
            tick.setStroke(SectionStyle.TICK);
            tick.setStrokeWidth(0.8);
            getChildren().add(tick);

            getChildren().add(
                    label(SectionStyle.formatTick(z, decimals),
                            geo.figureLeft() - 40, y - 7, SectionStyle.muted(10)));
        }

        getChildren().add(
                label("深度 / m", geo.figureLeft() - 44, geo.figureTop() - 20,
                        SectionStyle.muted(10)));
    }

    private void drawOffsetTicks() {

        double left = geo.leftOffsetM();
        double right = geo.rightOffsetM();

        double step = SectionStyle.niceStep((right - left) / 6);
        int decimals = SectionStyle.decimalsFor(step);

        int first = (int) Math.ceil(left / step - 1e-9);
        int last = (int) Math.floor(right / step + 1e-9);

        double bottom = geo.figureBottom();

        for (int k = first; k <= last; k++) {

            double offset = k * step;
            double x = geo.axisX() + offset * SectionGeometry.PX_PER_M;

            Line tick = new Line(x, bottom, x, bottom + 6);
            tick.setStroke(SectionStyle.TICK);
            tick.setStrokeWidth(0.8);
            getChildren().add(tick);

            getChildren().add(
                    label(SectionStyle.formatTick(offset, decimals),
                            x - 11, bottom + 8, SectionStyle.muted(10)));
        }

        getChildren().add(
                label("水平距离 / m", geo.axisX() - 34, bottom + 26, SectionStyle.muted(10)));
    }

    // ---------------- 点击查看某点的数据 ----------------

    private void createInfoBox() {

        infoBox = new Pane();
        infoBox.setMouseTransparent(true);

        Rectangle bg = new Rectangle(INFO_W, INFO_H);
        bg.setFill(Color.rgb(255, 255, 255, 0.94));
        bg.setStroke(SectionStyle.FRAME);
        bg.setStrokeWidth(1);
        bg.setArcWidth(6);
        bg.setArcHeight(6);

        infoText = new Label();
        infoText.setLayoutX(12);
        infoText.setLayoutY(8);
        infoText.getStyleClass().add("chart-info-text");

        infoBox.getChildren().addAll(bg, infoText);
        infoBox.setVisible(false);
        getChildren().add(infoBox);
    }

    private void handleClick(MouseEvent e) {

        double x = e.getX();
        double y = e.getY();

        if (!geo.insideFigure(x, y)) {
            infoBox.setVisible(false);
            return;
        }

        double depth = geo.yToZ(y);

        if (Math.abs(x - geo.axisX()) <= geo.wellRadiusPx()) {
            infoText.setText(String.format(
                    "深度 z: %.2f m\n角度 θ: —\n电阻率 ρ: %.0f",
                    depth, model.wellResistivity()));
        } else {
            int layer = geo.layerAt(x, y);
            infoText.setText(String.format(
                    "深度 z: %.2f m\n角度 θ: %.1f°\n电阻率 ρ: %.0f",
                    depth, geo.layerAngle(layer, x), model.resistivityOf(layer)));
        }

        positionInfoBox(x, y);
        infoBox.setVisible(true);
    }

    private void positionInfoBox(double x, double y) {

        double bx = x + 14;
        double by = y + 14;

        if (bx + INFO_W > getPrefWidth()) bx = x - INFO_W - 14;
        if (by + INFO_H > getPrefHeight()) by = y - INFO_H - 14;

        bx = Math.max(0, Math.min(bx, getPrefWidth() - INFO_W));
        by = Math.max(0, Math.min(by, getPrefHeight() - INFO_H));

        infoBox.setLayoutX(bx);
        infoBox.setLayoutY(by);
    }

    // ---------------- 小工具 ----------------

    private static Label label(String text, double x, double y, String style) {

        Label label = new Label(text);
        label.setLayoutX(x);
        label.setLayoutY(y);
        label.setStyle(style);
        return label;
    }
}
