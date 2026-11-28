package baritone.swarm.center.app;

import baritone.swarm.center.feed.SwarmView;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.function.Consumer;

/**
 * Interactive Replay Timeline scrubber bar. Lets the operator scrub back through the
 * last few minutes of recorded bot positions, paths, alerts, and command log lines
 * without interrupting live bot execution.
 *
 * <p>Names: {@code replay:panel}, {@code replay:slider}, {@code replay:play}, {@code replay:back},
 * {@code replay:fwd}, {@code replay:live}, {@code replay:status}, {@code replay:events}.
 */
final class ReplayPanel extends JPanel {

    private final ReplayBuffer buffer;
    private final Consumer<SwarmView> onScrubView;
    private final JSlider slider;
    private final JButton playBtn;
    private final JLabel statusLabel;
    private final JTextArea eventsArea;
    private final Timer playbackTimer;
    private boolean scrubbing;
    private boolean updatingSlider;

    ReplayPanel(AppStyle style, ReplayBuffer buffer, Consumer<SwarmView> onScrubView) {
        super(new BorderLayout(8, 4));
        this.buffer = buffer;
        this.onScrubView = onScrubView == null ? v -> {} : onScrubView;
        setName("replay:panel");
        setBackground(style.surface());
        setOpaque(true);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, style.line()),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        JPanel controls = style.panel(new BorderLayout(8, 0), null);
        JPanel buttons = style.panel(new FlowLayout(FlowLayout.LEFT, 4, 0), null);
        buttons.add(style.label("Replay", style.text(), Font.BOLD, 11.5f));

        JButton back = style.button("-10s");
        back.setName("replay:back");
        back.addActionListener(e -> step(-20));
        buttons.add(back);

        playBtn = style.button("Play");
        playBtn.setName("replay:play");
        playBtn.addActionListener(e -> togglePlay());
        buttons.add(playBtn);

        JButton fwd = style.button("+10s");
        fwd.setName("replay:fwd");
        fwd.addActionListener(e -> step(20));
        buttons.add(fwd);

        JButton live = style.button("Live");
        live.setName("replay:live");
        live.addActionListener(e -> returnToLive());
        buttons.add(live);

        statusLabel = style.label("LIVE (0 frames)", style.muted(), Font.PLAIN, 11f);
        statusLabel.setName("replay:status");
        buttons.add(statusLabel);

        slider = new JSlider(0, 0, 0);
        slider.setName("replay:slider");
        slider.setBackground(style.surface());
        slider.setFocusable(false);
        slider.addChangeListener(e -> {
            if (updatingSlider) {
                return;
            }
            int size = buffer.size();
            if (size <= 0) {
                return;
            }
            int idx = slider.getValue();
            if (idx >= size - 1) {
                scrubbing = false;
                stopPlay();
            } else {
                scrubbing = true;
            }
            emitFrame(idx);
        });

        controls.add(buttons, BorderLayout.WEST);
        controls.add(slider, BorderLayout.CENTER);
        add(controls, BorderLayout.NORTH);

        eventsArea = new JTextArea(4, 60);
        eventsArea.setName("replay:events");
        eventsArea.setEditable(false);
        eventsArea.setFocusable(false);
        eventsArea.setFont(AppStyle.font(Font.PLAIN, 11f));
        eventsArea.setBackground(style.bg());
        eventsArea.setForeground(style.text());
        eventsArea.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JScrollPane scroll = new JScrollPane(eventsArea);
        scroll.setBorder(BorderFactory.createLineBorder(style.line(), 1));
        scroll.setPreferredSize(new Dimension(200, 74));
        add(scroll, BorderLayout.CENTER);

        playbackTimer = new Timer(250, e -> {
            int size = buffer.size();
            if (!scrubbing || size <= 1) {
                stopPlay();
                return;
            }
            int next = slider.getValue() + 1;
            if (next >= size - 1) {
                returnToLive();
            } else {
                scrubTo(next);
            }
        });
    }

    boolean isScrubbing() {
        return scrubbing;
    }

    void onLiveFrameRecorded() {
        int size = buffer.size();
        updatingSlider = true;
        try {
            slider.setMaximum(Math.max(0, size - 1));
            if (!scrubbing) {
                slider.setValue(Math.max(0, size - 1));
            }
        } finally {
            updatingSlider = false;
        }
        if (!scrubbing) {
            ReplayBuffer.Frame latest = buffer.latest();
            statusLabel.setText("LIVE (" + size + " frames)");
            updateEvents(latest == null ? 0L : latest.timestampMs());
        }
    }

    void scrubTo(int index) {
        int size = buffer.size();
        if (size <= 0) {
            return;
        }
        int clamped = Math.max(0, Math.min(size - 1, index));
        scrubbing = clamped < size - 1;
        updatingSlider = true;
        try {
            slider.setMaximum(size - 1);
            slider.setValue(clamped);
        } finally {
            updatingSlider = false;
        }
        emitFrame(clamped);
    }

    void returnToLive() {
        stopPlay();
        scrubbing = false;
        int size = buffer.size();
        updatingSlider = true;
        try {
            slider.setMaximum(Math.max(0, size - 1));
            slider.setValue(Math.max(0, size - 1));
        } finally {
            updatingSlider = false;
        }
        ReplayBuffer.Frame latest = buffer.latest();
        statusLabel.setText("LIVE (" + size + " frames)");
        if (latest != null) {
            updateEvents(latest.timestampMs());
            onScrubView.accept(latest.view());
        }
    }

    private void step(int delta) {
        int size = buffer.size();
        if (size <= 1) {
            return;
        }
        stopPlay();
        scrubTo(slider.getValue() + delta);
    }

    private void togglePlay() {
        if (playbackTimer.isRunning()) {
            stopPlay();
            return;
        }
        if (buffer.size() <= 1) {
            return;
        }
        if (!scrubbing) {
            scrubTo(0);
        }
        playBtn.setText("Pause");
        playbackTimer.start();
    }

    private void stopPlay() {
        if (playbackTimer.isRunning()) {
            playbackTimer.stop();
        }
        playBtn.setText("Play");
    }

    private void emitFrame(int idx) {
        ReplayBuffer.Frame frame = buffer.frameAt(idx);
        ReplayBuffer.Frame latest = buffer.latest();
        if (frame == null) {
            return;
        }
        if (scrubbing && latest != null) {
            long agoSec = Math.max(0L, (latest.timestampMs() - frame.timestampMs()) / 1000L);
            statusLabel.setText("REPLAY -" + agoSec + "s (" + (idx + 1) + "/" + buffer.size() + ")");
        } else {
            statusLabel.setText("LIVE (" + buffer.size() + " frames)");
        }
        updateEvents(frame.timestampMs());
        onScrubView.accept(frame.view());
    }

    private void updateEvents(long ts) {
        List<ReplayBuffer.EventLine> list = buffer.eventsUpTo(ts, 12);
        if (list.isEmpty()) {
            eventsArea.setText("No recorded events yet.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (ReplayBuffer.EventLine e : list) {
            if (sb.length() > 0) sb.append('\n');
            long sec = (e.timestampMs() / 1000L) % 86_400L;
            long h = sec / 3600L;
            long m = (sec % 3600L) / 60L;
            long s = sec % 60L;
            sb.append(String.format("%02d:%02d:%02d [%s] ", h, m, s, e.level()));
            if (!e.bot().isEmpty()) {
                sb.append(e.bot()).append(" - ");
            }
            sb.append(e.message());
        }
        eventsArea.setText(sb.toString());
    }
}
