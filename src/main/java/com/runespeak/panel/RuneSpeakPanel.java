package com.runespeak.panel;

import com.runespeak.Language;
import com.runespeak.RuneSpeakConfig;
import com.runespeak.capture.ChatCapture;
import com.runespeak.capture.DialogCapture;
import com.runespeak.capture.MenuCapture;
import com.runespeak.capture.OverlayTextCapture;
import com.runespeak.capture.WidgetTextScanner;
import com.runespeak.translate.LocalTranslator;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.ColorScheme;

@Slf4j
@Singleton
public class RuneSpeakPanel extends PluginPanel {
    private final RuneSpeakConfig config;
    private final ConfigManager configManager;
    private final LocalTranslator translator;
    private final ChatCapture chatCapture;
    private final DialogCapture dialogCapture;
    private final MenuCapture menuCapture;
    private final OverlayTextCapture overlayTextCapture;
    private final WidgetTextScanner widgetTextScanner;

    private JLabel statusLabel;
    private JLabel modelLabel;
    private JLabel cacheCountLabel;
    private JTextArea translationLog;
    private JTextField cacheDirField;
    private Timer refreshTimer;

    @Inject
    public RuneSpeakPanel(RuneSpeakConfig config, ConfigManager configManager, LocalTranslator translator, ChatCapture chatCapture, DialogCapture dialogCapture, MenuCapture menuCapture, OverlayTextCapture overlayTextCapture, WidgetTextScanner widgetTextScanner) {
        this.config = config;
        this.configManager = configManager;
        this.translator = translator;
        this.chatCapture = chatCapture;
        this.dialogCapture = dialogCapture;
        this.menuCapture = menuCapture;
        this.overlayTextCapture = overlayTextCapture;
        this.widgetTextScanner = widgetTextScanner;
        initComponents();
        startRefreshTimer();
    }

    private void initComponents() {
        setLayout(new BorderLayout(0, 10));
        setBorder(new EmptyBorder(10, 10, 10, 10));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Top panel containing Header and Cache Settings
        JPanel topContainer = new JPanel();
        topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));
        topContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Top Header Info
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = new JLabel("RuneSpeak");
        title.setFont(new Font("Dialog", Font.BOLD, 18));
        title.setForeground(Color.WHITE);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerPanel.add(title);
        headerPanel.add(Box.createRigidArea(new Dimension(0, 5)));

        statusLabel = new JLabel("Status: Initializing...");
        statusLabel.setFont(new Font("Dialog", Font.PLAIN, 12));
        statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerPanel.add(statusLabel);
        headerPanel.add(Box.createRigidArea(new Dimension(0, 3)));

        modelLabel = new JLabel("Model: N/A");
        modelLabel.setFont(new Font("Dialog", Font.PLAIN, 11));
        modelLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        modelLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerPanel.add(modelLabel);
        headerPanel.add(Box.createRigidArea(new Dimension(0, 3)));

        cacheCountLabel = new JLabel("Cache: 0 entries");
        cacheCountLabel.setFont(new Font("Dialog", Font.PLAIN, 11));
        cacheCountLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        cacheCountLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerPanel.add(cacheCountLabel);

        topContainer.add(headerPanel);
        topContainer.add(Box.createRigidArea(new Dimension(0, 10)));

        // Cache Directory Settings Panel
        JPanel settingsPanel = new JPanel(new GridBagLayout());
        settingsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        settingsPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR),
                "Cache Directory",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font("Dialog", Font.BOLD, 11),
                Color.WHITE
        ));
        settingsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(3, 3, 3, 3);
        gbc.gridwidth = GridBagConstraints.REMAINDER;

        cacheDirField = new JTextField(12);
        cacheDirField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        cacheDirField.setForeground(Color.LIGHT_GRAY);
        cacheDirField.setCaretColor(Color.WHITE);
        
        String savedDir = config.getModelCacheDir();
        if (savedDir != null && !savedDir.isBlank()) {
            cacheDirField.setText(savedDir);
        } else {
            cacheDirField.setText(translator.getCacheDir().toAbsolutePath().toString());
        }
        settingsPanel.add(cacheDirField, gbc);

        JPanel dirButtons = new JPanel(new GridLayout(1, 2, 5, 0));
        dirButtons.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JButton browseButton = new JButton("Browse...");
        browseButton.setFont(new Font("Dialog", Font.PLAIN, 11));
        browseButton.addActionListener(e -> chooseCacheDir());
        dirButtons.add(browseButton);

        JButton applyDirBtn = new JButton("Apply");
        applyDirBtn.setFont(new Font("Dialog", Font.PLAIN, 11));
        applyDirBtn.addActionListener(e -> applyCacheDir());
        dirButtons.add(applyDirBtn);

        settingsPanel.add(dirButtons, gbc);
        topContainer.add(settingsPanel);

        add(topContainer, BorderLayout.NORTH);

        // Center Log Panel
        JPanel centerPanel = new JPanel(new BorderLayout(0, 5));
        centerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JLabel logTitle = new JLabel("Translation Log");
        logTitle.setFont(new Font("Dialog", Font.BOLD, 12));
        logTitle.setForeground(Color.WHITE);
        centerPanel.add(logTitle, BorderLayout.NORTH);

        translationLog = new JTextArea();
        translationLog.setEditable(false);
        translationLog.setFont(new Font("Monospaced", Font.PLAIN, 11));
        translationLog.setLineWrap(true);
        translationLog.setWrapStyleWord(true);
        translationLog.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        translationLog.setForeground(Color.LIGHT_GRAY);

        JScrollPane scrollPane = new JScrollPane(translationLog);
        scrollPane.setBorder(BorderFactory.createLineBorder(ColorScheme.DARKER_GRAY_COLOR));
        scrollPane.getVerticalScrollBar().setUnitIncrement(12);
        centerPanel.add(scrollPane, BorderLayout.CENTER);

        add(centerPanel, BorderLayout.CENTER);

        // Bottom Actions Panel (Stacked vertically)
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));
        bottomPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JButton clearLogBtn = new JButton("Clear Log");
        clearLogBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        clearLogBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        clearLogBtn.addActionListener(e -> {
            translationLog.setText("");
            chatCapture.clear();
            dialogCapture.clear();
        });
        bottomPanel.add(clearLogBtn);
        bottomPanel.add(Box.createRigidArea(new Dimension(0, 5)));

        JButton clearCacheBtn = new JButton("Clear Cache");
        clearCacheBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        clearCacheBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        clearCacheBtn.addActionListener(e -> {
            translator.getCache().clear();
            overlayTextCapture.clear();
            widgetTextScanner.clear();
            menuCapture.clear();
            dialogCapture.clear();
            chatCapture.clear();
            refreshStatus();
        });
        bottomPanel.add(clearCacheBtn);
        bottomPanel.add(Box.createRigidArea(new Dimension(0, 5)));

        JButton reloadBtn = new JButton("Reload Model");
        reloadBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        reloadBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        reloadBtn.addActionListener(e -> {
            reloadBtn.setEnabled(false);
            statusLabel.setText("Status: Loading model...");
            translator.initialize(readModelId());
            reloadBtn.setEnabled(true);
        });
        bottomPanel.add(reloadBtn);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void chooseCacheDir() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select Cache Directory");
        String current = cacheDirField.getText().trim();
        if (!current.isEmpty()) {
            chooser.setCurrentDirectory(new File(current));
        }
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            cacheDirField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void applyCacheDir() {
        String dir = cacheDirField.getText().trim();
        if (!dir.isEmpty()) {
            configManager.setConfiguration("runespeak", "modelCacheDir", dir);
            log.info("Cache directory updated to: {}", dir);
            statusLabel.setText("Status: Loading model...");
            translator.initialize(readModelId());
        }
    }

    private void startRefreshTimer() {
        refreshTimer = new Timer(1000, e -> refreshStatus());
        refreshTimer.start();
    }

    private void refreshStatus() {
        if (translator.isReady()) {
            statusLabel.setText("Status: Ready");
        } else if (translator.isLoading()) {
            statusLabel.setText("Status: Loading model...");
        } else {
            statusLabel.setText("Status: Model not loaded");
        }

        modelLabel.setText("Model: " + readModelId());
        cacheCountLabel.setText("Cache: " + translator.getCache().size()
                + " / " + translator.getCache().getMaxSize() + " entries");

        // Merge chat translations and dialog translations into one unified log
        List<String> logLines = new ArrayList<>();

        List<DialogCapture.DialogLogEntry> dialogEntries = new ArrayList<>(dialogCapture.getDialogLog());
        int dlgStart = Math.max(0, dialogEntries.size() - 10);
        for (DialogCapture.DialogLogEntry e : dialogEntries.subList(dlgStart, dialogEntries.size())) {
            logLines.add(String.format("[NPC] %s\n  \u2192 %s",
                    truncate(e.getOriginal(), 40),
                    truncate(e.getTranslation(), 40)));
        }

        List<ChatCapture.TranslatedMessage> messages;
        synchronized (chatCapture.getTranslatedMessages()) {
            messages = List.copyOf(chatCapture.getTranslatedMessages());
        }
        int chatStart = Math.max(0, messages.size() - 10);
        for (ChatCapture.TranslatedMessage msg : messages.subList(chatStart, messages.size())) {
            logLines.add(String.format("[%s] %s\n  \u2192 %s",
                    msg.getSender(),
                    truncate(msg.getOriginal(), 40),
                    truncate(msg.getTranslation(), 40)));
        }

        if (!logLines.isEmpty()) {
            translationLog.setText(String.join("\n---\n", logLines));
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    private String readModelId() {
        String current = translator.getEngine().getCurrentModelId();
        if (current == null || current.isBlank()) {
            Language source = config.getSourceLanguage();
            Language target = config.getTargetLanguage();
            if (source == target) {
                return "N/A (Source == Target)";
            }
            return "onnx-community/opus-mt-" + source.getTwoLetterCode() + "-" + target.getTwoLetterCode();
        }
        return current;
    }

    public void shutdown() {
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
    }
}
