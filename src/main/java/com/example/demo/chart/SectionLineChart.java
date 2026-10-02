package com.example.demo.chart;

import com.example.demo.model.StratumInterface;
import com.example.demo.model.StratumModel;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

/**
 * 只画线条的地层界面图：图框 + 深度/水平距离刻度 + 井壁轮廓 + 各条界面直线。
 *
 * <p>不含地层色块和色标，用来单独看界面的几何关系。
 */
public final class SectionLineChart extends Pane {

    private static final double TITLE_H = 30;
    private static final double MARGIN_LEFT = 46;
    private static final double MARGIN_RIGHT = 26;
    private static final double MARGIN_BOTTOM = 44;

    /** 井壁虚线的短划长度。 */
    private static final double DASH = 6;

    private final StratumModel model;
    private final SectionGeometry geo;

    public SectionLineChart(StratumModel model) {

        this.model = model;
        this.geo = new SectionGeometry(model, MARGIN_LEFT, TITLE_H);

        setPrefSize(
                MARGIN_LEFT + geo.figureWidth() + MARGIN_RIGHT,
                TITLE_H + geo.figureHeight() + MARGIN_BOTTOM
        );
        getStyleClass().add("chart-canvas");

        drawAxes();
        drawFrame();
        drawWell();
        drawInterfaces();
        drawDepthLabels();
    }

    // ---------------- 图框 ----------------

    private void drawFrame() {

        Rectangle frame = new Rectangle(
                geo.figureLeft(), geo.figureTop(),
                geo.figureWidth(), geo.figureHeight());

        frame.setFill(null);
        frame.setStroke(SectionStyle.FRAME);
        frame.setStrokeWidth(1.5);

        getChildren().add(frame);
    }

    // ---------------- 井筒轮廓 ----------------

    private void drawWell() {

        double axisX = geo.axisX();
        double top = geo.figureTop();
        double bottom = geo.figureBottom();
        double r = geo.wellRadiusPx();

        // 井壁：左右两条虚线
        for (double x : new double[]{axisX - r, axisX + r}) {

            Line wall = new Line(x, top, x, bottom);
            wall.setStroke(SectionStyle.WELL_OUTLINE);
            wall.setStrokeWidth(1);
            wall.getStrokeDashArray().addAll(DASH, DASH);

            getChildren().add(wall);
        }

        // 井轴：更浅的一条点划线
        Line axis = new Line(axisX, top, axisX, bottom);
        axis.setStroke(SectionStyle.WELL_AXIS);
        axis.setStrokeWidth(0.8);
        axis.getStrokeDashArray().addAll(2.0, 4.0);

        getChildren().add(axis);
    }

    // ---------------- 界面直线 ----------------

    private void drawInterfaces() {

        for (StratumInterface s : model.interfaces()) {

            Line line = geo.createLine(geo.axisX(), geo.zToY(s.depth()), s.angle());

            line.setStroke(SectionStyle.INTERFACE_LINE);
            line.setStrokeWidth(1.4);

            getChildren().add(line);
        }
    }

    // ---------------- 井轴处的交点标注 ----------------

    private void drawDepthLabels() {

        double x = geo.axisX() + geo.wellRadiusPx() + 8;

        for (StratumInterface s : model.interfaces()) {

            String text = String.format("%.2f m / %.0f°", s.depth(), s.angle());

            getChildren().add(
                    label(text, x, geo.zToY(s.depth()) - 19, SectionStyle.chip(11)));
        }
    }

    // ---------------- 标题 ----------------

    private void drawTitle() {

        getChildren().add(
                label("地层界面（仅线条）",
                        geo.axisX() - 62, 14, SectionStyle.title(18)));
    }

    // ---------------- 边框刻度 ----------------

    private void drawAxes() {

        drawDepthTicks();
        drawOffsetTicks();
    }

    /** 左侧：深度刻度。 */
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

    /** 底部：水平距离刻度，以井轴为 0。 */
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
                label("水平距离 / m", geo.axisX() - 34, bottom + 26,
                        SectionStyle.muted(10)));
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
