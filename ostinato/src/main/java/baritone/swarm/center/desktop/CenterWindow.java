package baritone.swarm.center.desktop;

import baritone.Baritone;
import baritone.api.command.ArgDecl;
import baritone.api.command.FormPlacement;
import baritone.api.command.manager.ICommandManager;
import baritone.swarm.center.BotSnapshot;
import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterRuntime;
import baritone.swarm.center.CommandCenterModel;
import baritone.swarm.center.FocusPolicy;
import baritone.swarm.center.LiveTiles;
import baritone.swarm.center.RegistryCall;
import baritone.swarm.center.TileFormatter;
import baritone.swarm.center.UnifiedChat;
import baritone.swarm.center.WindowPlacement;
import baritone.swarm.spawn.SwarmSpawnRegistry;
import net.minecraft.client.Minecraft;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: the command-center desktop window. It is not an {@code OstinatoScreen}.
 * Off until {@code #center} or {@link CenterRuntime#open}. Closing hides it and does not despawn.
 * The frame is never created in unit tests: {@link #mayShow()} is false without a display.
 */
public final class CenterWindow implements CenterRuntime.Host {

    private static final Color BG = new Color(0x1E1E1E);
    private static final Color TILE = new Color(0x2C2C2C);
    private static final Color TEXT = new Color(0xE6E6E6);
    private static final Color MUTED = new Color(0xA0A0A0);
    private static final CenterWindow INSTANCE = new CenterWindow();

    private ICommandManager commands;
    private CommandCenterModel model;
    private JFrame frame;
    private JLabel serverLabel;
    private JPanel wall;
    private JPanel cards;
    private CardLayout cardLayout;
    private JPanel accounts;
    private Timer timer;
    private List<String> shownNames = List.of();
    private final Map<String, JLabel> bodies = new LinkedHashMap<>();

    private CenterWindow() {}

    /** Documented hook partner. Does not open the window. */
    public static void install(ICommandManager manager) {
        INSTANCE.commands = manager;
        CenterRuntime.setHost(INSTANCE);
    }

    /** False on a headless box and when {@code -Dostinato.center.show=false}. */
    public static boolean mayShow() {
        return !GraphicsEnvironment.isHeadless()
                && !"false".equalsIgnoreCase(System.getProperty("ostinato.center.show", "true"));
    }

    @Override
    public void open() {
        if (!mayShow()) {
            return;
        }
        SwingUtilities.invokeLater(this::show);
    }

    @Override
    public void close() {
        if (frame == null) {
            return;
        }
        SwingUtilities.invokeLater(this::hide);
    }

    @Override
    public void layout(CenterLayout layout) {
        if (model != null) {
            model.show(layout);
        }
        if (frame == null || cardLayout == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> showCard(layout));
    }

    private void show() {
        if (!mayShow()) {
            return;
        }
        ensureModel();
        if (frame == null) {
            frame = build();
        }
        place(frame);
        refresh();
        showCard(model.layout());
        // Ostinato: show without activating. A later click may focus a field.
        frame.setAutoRequestFocus(FocusPolicy.AUTO_REQUEST_FOCUS);
        frame.setFocusableWindowState(false);
        frame.setVisible(true);
        frame.setFocusableWindowState(FocusPolicy.FOCUSABLE_AFTER_SHOW);
        if (timer != null) {
            timer.start();
        }
    }

    private void hide() {
        if (timer != null) {
            timer.stop();
        }
        if (frame != null) {
            frame.setVisible(false);
        }
    }

    private void ensureModel() {
        if (model == null) {
            model = new CommandCenterModel(UnifiedChat.registry(), UnifiedChat.configDir());
            model.show(CenterRuntime.layout());
        }
    }

    private JFrame build() {
        JFrame window = new JFrame("Ostinato command center");
        window.setAutoRequestFocus(FocusPolicy.AUTO_REQUEST_FOCUS);
        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                CenterRuntime.close();
            }
        });
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBackground(BG);
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(header(), BorderLayout.NORTH);
        cardLayout = new CardLayout();
        cards = new JPanel(cardLayout);
        cards.setBackground(BG);
        wall = new JPanel(new GridLayout(0, 3, 8, 8));
        wall.setBackground(BG);
        JScrollPane scroll = new JScrollPane(wall);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BG);
        cards.add(scroll, CenterLayout.WALL.wire());
        JLabel war = label("War room is a placeholder in v1. No map and no click-to-command.", TEXT);
        JPanel warPanel = new JPanel(new BorderLayout());
        warPanel.setBackground(BG);
        warPanel.add(war, BorderLayout.NORTH);
        cards.add(warPanel, CenterLayout.MAP.wire());
        root.add(cards, BorderLayout.CENTER);
        accounts = new JPanel(new FlowLayout(FlowLayout.LEFT));
        accounts.setBackground(BG);
        accounts.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(MUTED), "Accounts", 0, 0, null, TEXT));
        root.add(accounts, BorderLayout.SOUTH);
        try {
            AccountPanel.attach(accounts, UnifiedChat.configDir());
        } catch (RuntimeException ignored) {
            // The wall still opens. Sign-in reports the reason on the panel when it can.
        }
        window.setContentPane(root);
        window.setSize(980, 640);
        window.getContentPane().setBackground(BG);
        timer = new Timer(2000, event -> refresh());
        timer.setRepeats(true);
        return window;
    }

    private JComponent header() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(BG);
        JPanel titles = new JPanel(new GridLayout(2, 1));
        titles.setBackground(BG);
        titles.add(label("Mission control", TEXT));
        serverLabel = label("no server", MUTED);
        titles.add(serverLabel);
        header.add(titles, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.setBackground(BG);
        for (ButtonModel button : model.buttons(FormPlacement.HEADER)) {
            actions.add(actionButton(button, null));
        }
        for (ButtonModel button : model.buttons(FormPlacement.WINDOW)) {
            actions.add(form(button, null));
        }
        JButton layout = new JButton("Wall / war room");
        style(layout);
        layout.addActionListener(event -> toggleLayout());
        actions.add(layout);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private void toggleLayout() {
        CenterLayout next = model.layout() == CenterLayout.WALL ? CenterLayout.MAP : CenterLayout.WALL;
        ButtonModel button = null;
        for (ButtonModel candidate : model.buttons(FormPlacement.HEADER)) {
            if (("layout-" + next.wire()).equals(candidate.form().id())) {
                button = candidate;
                break;
            }
        }
        if (button == null) {
            try {
                model.remember(next);
            } catch (java.io.IOException ignored) {
                // The card stays. The chat command reports a save failure.
            }
            return;
        }
        ButtonModel chosen = button;
        model.press(caller(""), chosen, Map.of(), null, line -> commands != null && commands.execute(line));
    }

    private JComponent form(ButtonModel button, String bot) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBackground(BG);
        Map<String, JTextField> fields = new LinkedHashMap<>();
        for (ArgDecl arg : button.form().args()) {
            panel.add(label(arg.name(), MUTED));
            JTextField field = new JTextField(8);
            fields.put(arg.name(), field);
            panel.add(field);
        }
        JButton go = new JButton(button.form().label());
        style(go);
        go.addActionListener(event -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<String, JTextField> entry : fields.entrySet()) {
                values.put(entry.getKey(), entry.getValue().getText());
            }
            if (bot != null && (values.get("name") == null || values.get("name").isBlank())) {
                values.put("name", bot);
            }
            dispatch(button, values, bot);
        });
        panel.add(go);
        return panel;
    }

    private JButton actionButton(ButtonModel button, String bot) {
        JButton go = new JButton(button.form().label());
        style(go);
        go.addActionListener(event -> dispatch(button, bot == null ? Map.of() : Map.of("name", bot), bot));
        return go;
    }

    private void dispatch(ButtonModel button, Map<String, String> values, String bot) {
        model.press(caller(bot), button, values, bot, line -> commands != null && commands.execute(line));
    }

    private RegistryCall caller(String bot) {
        String actor = "local";
        String owner = "";
        try {
            owner = Baritone.settings().agentOwner.value;
        } catch (RuntimeException ignored) {
            owner = "";
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                actor = mc.player.getName().getString();
            }
        } catch (RuntimeException ignored) {
            actor = "local";
        }
        RegistryCall call = RegistryCall.window(actor, owner, "");
        return bot == null || bot.isBlank() ? call : call.withScope(baritone.swarm.center.RegistryCall.Scope.ONE, bot);
    }

    private void refresh() {
        if (model == null || wall == null) {
            return;
        }
        List<BotSnapshot> tiles = readTiles();
        model.tiles(tiles);
        String server = LiveTiles.serverLabel(readServer(), tiles);
        model.server(server);
        if (serverLabel != null) {
            serverLabel.setText(server);
        }
        List<String> names = tiles.stream().map(BotSnapshot::name).toList();
        if (!names.equals(shownNames)) {
            rebuild(tiles);
            shownNames = names;
        } else {
            for (BotSnapshot tile : tiles) {
                JLabel body = bodies.get(tile.name());
                if (body != null) {
                    body.setText(html(tile));
                }
            }
        }
    }

    private void rebuild(List<BotSnapshot> tiles) {
        wall.removeAll();
        bodies.clear();
        for (BotSnapshot tile : tiles) {
            wall.add(tilePanel(tile));
        }
        wall.revalidate();
        wall.repaint();
    }

    private JComponent tilePanel(BotSnapshot tile) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBackground(TILE);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JLabel body = label(html(tile), TEXT);
        bodies.put(tile.name(), body);
        panel.add(body, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.setBackground(TILE);
        for (ButtonModel button : model.buttons(FormPlacement.TILE)) {
            if (button.form().args().isEmpty()) {
                actions.add(actionButton(button, tile.name()));
            } else {
                actions.add(form(button, tile.name()));
            }
        }
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private static String html(BotSnapshot tile) {
        StringBuilder sb = new StringBuilder("<html>");
        List<String> lines = TileFormatter.lines(tile);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append("<br>");
            }
            sb.append(escape(lines.get(i)));
        }
        sb.append("</html>");
        return sb.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private List<BotSnapshot> readTiles() {
        BotSnapshot local = null;
        String server = readServer();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                int health = (int) mc.player.getHealth();
                int hunger = mc.player.getFoodData().getFoodLevel();
                local = LiveTiles.localPlayer(mc.player.getName().getString(), health, hunger, server);
            }
        } catch (RuntimeException ignored) {
            local = null;
        }
        List<BotSnapshot> children = new ArrayList<>();
        for (SwarmSpawnRegistry.Child child : SwarmSpawnRegistry.INSTANCE.list()) {
            boolean alive = child.process() != null && child.process().isAlive();
            children.add(LiveTiles.child(child.name(), child.server(), alive));
        }
        return LiveTiles.compose(local, children);
    }

    private static String readServer() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
                return mc.getCurrentServer().ip;
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        return "";
    }

    private void showCard(CenterLayout layout) {
        if (cardLayout == null || cards == null) {
            return;
        }
        CenterLayout use = layout == null ? CenterLayout.WALL : layout;
        cardLayout.show(cards, use.wire());
    }

    private static void place(JFrame window) {
        List<WindowPlacement.Screen> screens = new ArrayList<>();
        if (!GraphicsEnvironment.isHeadless()) {
            for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle bounds = device.getDefaultConfiguration().getBounds();
                screens.add(new WindowPlacement.Screen(bounds.x, bounds.y, bounds.width, bounds.height));
            }
        }
        int[] at = WindowPlacement.place(screens, System.getProperty("ostinato.windowPos"));
        window.setLocation(at[0], at[1]);
    }

    private static JLabel label(String text, Color color) {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        return label;
    }

    private static void style(JButton button) {
        button.setBackground(new Color(0x3A3A3A));
        button.setForeground(TEXT);
        button.setFocusable(true);
    }

    /** The account manager fills this panel. Null until the frame exists. */
    public static JPanel accountsPanel() {
        return INSTANCE.accounts;
    }
}
