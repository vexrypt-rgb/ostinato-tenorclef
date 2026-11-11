package baritone.swarm.center.desktop;

import baritone.swarm.accounts.AccountPanelModel;
import baritone.swarm.accounts.AccountStore;
import baritone.swarm.accounts.AzureClientConfig;
import baritone.swarm.accounts.DpapiProtector;
import baritone.swarm.accounts.JdkHttpExchange;
import baritone.swarm.accounts.MicrosoftAuth;
import baritone.swarm.accounts.SecretProtector;
import baritone.swarm.center.UnifiedChat;
import baritone.swarm.spawn.SwarmSpawnAccounts;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Timer;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Ostinato: add and remove bot accounts on the command center. Sign-in does not start without a client id.
 * The status line never receives a token.
 */
public final class AccountPanel {

    private AccountPanel() {}

    public static void attach(JPanel host, Path configDir) {
        host.removeAll();
        SecretProtector protector = null;
        try {
            protector = DpapiProtector.platform();
        } catch (RuntimeException ignored) {
            protector = null;
        }
        Path root = configDir == null ? UnifiedChat.configDir() : configDir;
        AccountStore store = new AccountStore(root.resolve("accounts"), protector);
        AccountPanelModel model = new AccountPanelModel(store, AzureClientConfig.clientId(root), protector != null);
        MicrosoftAuth.HttpExchange http = new JdkHttpExchange();
        DefaultListModel<String> names = new DefaultListModel<>();
        JList<String> list = new JList<>(names);
        reload(store, names);
        JLabel status = new JLabel(protector == null ? "Windows DPAPI is not available" : " ");
        status.setForeground(new java.awt.Color(0xE6E6E6));
        JTextField offline = new JTextField(10);
        JButton add = new JButton("Add offline");
        add.addActionListener(event -> {
            try {
                store.addOffline(offline.getText().trim());
                offline.setText("");
                reload(store, names);
                status.setText("Offline account added");
            } catch (RuntimeException | IOException e) {
                status.setText(plain(e));
            }
        });
        JButton remove = new JButton("Remove");
        remove.addActionListener(event -> {
            String selected = list.getSelectedValue();
            if (selected == null) {
                return;
            }
            int mark = selected.indexOf(" (");
            String name = mark > 0 ? selected.substring(0, mark) : selected;
            try {
                store.remove(name);
                reload(store, names);
                status.setText("Removed " + name);
            } catch (IOException e) {
                status.setText("could not remove account");
            }
        });
        JButton importLauncher = new JButton("Import launcher");
        importLauncher.addActionListener(event -> {
            try {
                int count = store.importAll(SwarmSpawnAccounts.launcherFiles(null));
                reload(store, names);
                status.setText("Imported " + count + " account(s)");
            } catch (IOException e) {
                status.setText(plain(e));
            }
        });
        Timer timer = new Timer(5000, event -> {
            String line = model.pollOnce(http);
            status.setText(line);
            if (!line.startsWith("Open ")) {
                ((Timer) event.getSource()).stop();
                reload(store, names);
            }
        });
        timer.setRepeats(true);
        JButton signIn = new JButton("Microsoft sign-in");
        signIn.addActionListener(event -> {
            String line = model.beginSignIn(http);
            status.setText(line);
            if (line.startsWith("Open ")) {
                timer.setDelay(model.intervalSeconds() * 1000);
                timer.start();
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.setOpaque(false);
        buttons.add(offline);
        buttons.add(add);
        buttons.add(remove);
        buttons.add(importLauncher);
        buttons.add(signIn);
        host.setLayout(new java.awt.BorderLayout());
        host.add(new JScrollPane(list), java.awt.BorderLayout.CENTER);
        host.add(buttons, java.awt.BorderLayout.NORTH);
        host.add(status, java.awt.BorderLayout.SOUTH);
    }

    private static void reload(AccountStore store, DefaultListModel<String> names) {
        names.clear();
        for (AccountStore.Listed listed : store.list()) {
            String kind = listed.kind() == AccountStore.Kind.OFFLINE ? "offline" : "microsoft";
            names.addElement(listed.name() + " (" + kind + ")");
        }
    }

    private static String plain(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "could not update accounts";
        }
        if (message.startsWith("Account name") || message.startsWith("Windows DPAPI")
                || message.startsWith("could not") || message.startsWith("Online-mode")
                || message.startsWith("No offline") || message.startsWith("Sign-in needs")
                || message.startsWith("One account")) {
            return message;
        }
        return "could not update accounts";
    }
}
