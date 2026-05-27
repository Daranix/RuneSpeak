package com.runespeak.panel;

import com.runespeak.Language;
import com.runespeak.RuneSpeakConfig;
import com.runespeak.capture.ChatCapture;
import com.runespeak.translate.LocalTranslator;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.PluginPanel;

@Slf4j
@Singleton
public class RuneSpeakPanel extends PluginPanel {
    private final RuneSpeakConfig config;
    private final ConfigManager configManager;
    private final LocalTranslator translator;
    private final ChatCapture chatCapture;

    private JLabel statusLabel;
    private JLabel modelLabel;
    private JLabel cacheCountLabel;
    private JTextArea translationLog;
    private Timer refreshTimer;

    private static final String[] MODEL_PRESETS = {
            "Xenova/opus-mt-en-es",
            "onnx-community/Qwen2.5-0.5B-Instruct-ONNX",
            "onnx-community/Llama-3.2-1B-Instruct-ONNX",
            "onnx-community/t5-small-ONNX",
            "echarlaix/t5-small-onnx",
    };

    private JComboBox<Language> sourceCombo;
    private JComboBox<Language> targetCombo;
    private JComboBox<String> modelCombo;
    private JSpinner cacheSizeSpinner;
    private JTextField cacheDirField;
    private JButton applyButton;

    @Inject
    public RuneSpeakPanel(RuneSpeakConfig config, ConfigManager configManager, LocalTranslator translator, ChatCapture chatCapture) {
        this.config = config;
        this.configManager = configManager;
        this.translator = translator;
        this.chatCapture = chatCapture;
        initComponents();
        startRefreshTimer();
    }

    private void initComponents() {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel scrollContent = new JPanel();
        scrollContent.setLayout(new BoxLayout(scrollContent, BoxLayout.Y_AXIS));
        addHeader(scrollContent);
        addSettingsSection(scrollContent);
        addLogSection(scrollContent);

        JScrollPane scrollPane = new JScrollPane(scrollContent);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);

        addButtonPanel();
    }

    private void addHeader(JPanel parent) {
        JPanel header = new JPanel(new GridBagLayout());
        header.setBorder(new EmptyBorder(0, 0, 8, 0));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(1, 0, 1, 0);

        JLabel title = new JLabel("RuneSpeak");
        title.setFont(new Font("Dialog", Font.BOLD, 18));
        header.add(title, gbc);

        statusLabel = new JLabel("Status: Initializing...");
        statusLabel.setFont(new Font("Dialog", Font.PLAIN, 12));
        header.add(statusLabel, gbc);

        modelLabel = new JLabel("Model: " + readModelId());
        modelLabel.setFont(new Font("Dialog", Font.PLAIN, 10));
        header.add(modelLabel, gbc);

        cacheCountLabel = new JLabel("Cache: 0 entries");
        cacheCountLabel.setFont(new Font("Dialog", Font.PLAIN, 10));
        header.add(cacheCountLabel, gbc);

        parent.add(header);
    }

    private void addSettingsSection(JPanel parent) {
        JPanel section = new JPanel(new GridBagLayout());
        section.setBorder(new TitledBorder("Translation Settings"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(3, 6, 3, 6);
        gbc.gridwidth = GridBagConstraints.REMAINDER;

        Language[] languages = Language.values();

        JPanel langRow = new JPanel(new GridBagLayout());
        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(0, 0, 0, 2);

        lc.gridx = 0; lc.weightx = 0; lc.fill = GridBagConstraints.NONE;
        langRow.add(new JLabel("From:"), lc);

        sourceCombo = new JComboBox<>(languages);
        sourceCombo.setSelectedItem(config.getSourceLanguage());
        lc.gridx = 1; lc.weightx = 0.5; lc.fill = GridBagConstraints.HORIZONTAL;
        langRow.add(sourceCombo, lc);

        lc.gridx = 2; lc.weightx = 0; lc.fill = GridBagConstraints.NONE;
        langRow.add(new JLabel("\u00a0To:"), lc);

        targetCombo = new JComboBox<>(languages);
        targetCombo.setSelectedItem(config.getTargetLanguage());
        lc.gridx = 3; lc.weightx = 0.5; lc.fill = GridBagConstraints.HORIZONTAL;
        langRow.add(targetCombo, lc);

        section.add(langRow, gbc);

        JPanel modelRow = new JPanel(new GridBagLayout());
        GridBagConstraints mc = new GridBagConstraints();
        mc.anchor = GridBagConstraints.WEST;
        mc.insets = new Insets(0, 0, 0, 2);

        mc.gridx = 0; mc.weightx = 0; mc.fill = GridBagConstraints.NONE;
        modelRow.add(new JLabel("Model:"), mc);

        modelCombo = new JComboBox<>(MODEL_PRESETS);
        modelCombo.setEditable(true);
        String currentModel = readModelId();
        boolean found = false;
        for (int i = 0; i < MODEL_PRESETS.length; i++) {
            if (MODEL_PRESETS[i].equals(currentModel)) {
                modelCombo.setSelectedIndex(i);
                found = true;
                break;
            }
        }
        if (!found) {
            modelCombo.setSelectedItem(currentModel);
        }
        mc.gridx = 1; mc.weightx = 1.0; mc.fill = GridBagConstraints.HORIZONTAL;
        modelRow.add(modelCombo, mc);
        section.add(modelRow, gbc);

        JPanel cacheRow = new JPanel(new GridBagLayout());
        GridBagConstraints cc = new GridBagConstraints();
        cc.anchor = GridBagConstraints.WEST;
        cc.insets = new Insets(0, 0, 0, 2);

        cc.gridx = 0; cc.weightx = 0; cc.fill = GridBagConstraints.NONE;
        cacheRow.add(new JLabel("Max cache:"), cc);

        cacheSizeSpinner = new JSpinner(new SpinnerNumberModel(readCacheSize(), 100, 100_000, 500));
        cc.gridx = 1; cc.weightx = 0; cc.fill = GridBagConstraints.HORIZONTAL;
        cacheRow.add(cacheSizeSpinner, cc);

        cc.gridx = 2; cc.weightx = 1.0; cc.fill = GridBagConstraints.NONE;
        cacheRow.add(new JLabel(" entries"), cc);
        section.add(cacheRow, gbc);

        JPanel dirLabelRow = new JPanel(new GridBagLayout());
        GridBagConstraints dc = new GridBagConstraints();
        dc.anchor = GridBagConstraints.WEST;
        dirLabelRow.add(new JLabel("Cache dir:"), dc);
        section.add(dirLabelRow, gbc);

        JPanel dirRow = new JPanel(new GridBagLayout());
        GridBagConstraints dirc = new GridBagConstraints();
        dirc.insets = new Insets(0, 0, 0, 2);

        cacheDirField = new JTextField();
        String savedDir = configManager.getConfiguration("runespeak", "modelCacheDir");
        if (savedDir != null && !savedDir.isBlank()) {
            cacheDirField.setText(Path.of(savedDir).toAbsolutePath().toString());
        } else {
            cacheDirField.setText(translator.getCacheDir().toAbsolutePath().toString());
        }
        dirc.gridx = 0; dirc.weightx = 1.0; dirc.fill = GridBagConstraints.HORIZONTAL;
        dirRow.add(cacheDirField, dirc);

        JButton browseButton = new JButton("Browse...");
        browseButton.addActionListener(e -> chooseCacheDir());
        dirc.gridx = 1; dirc.weightx = 0; dirc.fill = GridBagConstraints.NONE;
        dirRow.add(browseButton, dirc);
        section.add(dirRow, gbc);

        JPanel applyRow = new JPanel(new GridBagLayout());
        GridBagConstraints ac = new GridBagConstraints();
        ac.anchor = GridBagConstraints.WEST;
        ac.insets = new Insets(0, 0, 0, 4);

        applyButton = new JButton("Apply Settings");
        applyButton.addActionListener(e -> applySettings());
        ac.gridx = 0;
        applyRow.add(applyButton, ac);

        JButton refreshCacheBtn = new JButton("Clear Cache");
            refreshCacheBtn.setToolTipText("Remove all cached translations");
            refreshCacheBtn.addActionListener(e -> {
            translator.getCache().clear();
            refreshStatus();
        });
        ac.gridx = 1; ac.insets = new Insets(0, 0, 0, 0);
        applyRow.add(refreshCacheBtn, ac);

        section.add(applyRow, gbc);
        parent.add(section);
    }

    private void addLogSection(JPanel parent) {
        JPanel section = new JPanel(new BorderLayout());
        section.setBorder(new TitledBorder("Translation Log"));

        translationLog = new JTextArea();
        translationLog.setEditable(false);
        translationLog.setFont(new Font("Monospaced", Font.PLAIN, 11));
        translationLog.setLineWrap(true);
        translationLog.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(translationLog);
        scrollPane.setPreferredSize(new Dimension(0, 200));
        section.add(scrollPane, BorderLayout.CENTER);

        parent.add(section);
    }

    private void addButtonPanel() {
        JPanel buttonPanel = new JPanel(new GridBagLayout());
        GridBagConstraints bc = new GridBagConstraints();
        bc.anchor = GridBagConstraints.WEST;
        bc.insets = new Insets(0, 0, 0, 4);

        JButton clearButton = new JButton("Clear Log");
        clearButton.addActionListener(e -> {
            translationLog.setText("");
            chatCapture.clear();
        });
        bc.gridx = 0;
        buttonPanel.add(clearButton, bc);

        JButton reloadButton = new JButton("Reload Model");
        reloadButton.addActionListener(e -> {
            reloadButton.setEnabled(false);
            statusLabel.setText("Status: Loading model...");
            translator.initialize(readModelId());
        });
        bc.gridx = 1; bc.insets = new Insets(0, 0, 0, 0);
        buttonPanel.add(reloadButton, bc);

        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void chooseCacheDir() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select Cache Directory");
        String current = cacheDirField.getText().trim();
        if (!current.isEmpty()) {
            chooser.setCurrentDirectory(new java.io.File(current));
        }
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            cacheDirField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void applySettings() {
        Language src = (Language) sourceCombo.getSelectedItem();
        Language tgt = (Language) targetCombo.getSelectedItem();
        translator.setLanguages(src, tgt);

        int newSize = (Integer) cacheSizeSpinner.getValue();
        translator.getCache().setMaxSize(newSize);
        configManager.setConfiguration("runespeak", "cacheSize", String.valueOf(newSize));

        String dir = cacheDirField.getText().trim();
        if (!dir.isEmpty()) {
            configManager.setConfiguration("runespeak", "modelCacheDir", dir);
            log.info("Cache directory set to: {}", dir);
        }

        String selectedModel = (String) modelCombo.getSelectedItem();
        boolean modelChanged = !selectedModel.equals(readModelId());
        if (modelChanged) {
            configManager.setConfiguration("runespeak", "modelId", selectedModel);
            statusLabel.setText("Status: Loading model...");
            translator.initialize(selectedModel);
        }

        log.info("Settings applied — {} → {}, model: {}, cache: {} max",
                src.getDisplayName(), tgt.getDisplayName(), selectedModel, newSize);
        applyButton.setText("Applied \u2713");
        Timer reset = new Timer(2000, e -> applyButton.setText("Apply Settings"));
        reset.setRepeats(false);
        reset.start();
    }

    private void startRefreshTimer() {
        refreshTimer = new Timer(1000, e -> refreshStatus());
        refreshTimer.start();
    }

    private void refreshStatus() {
        if (translator.isReady()) {
            statusLabel.setText("Status: Ready (ONNX Runtime)");
        } else if (translator.isLoading()) {
            statusLabel.setText("Status: Loading model (downloading if needed)...");
        } else {
            statusLabel.setText("Status: Waiting — model not loaded");
        }

        modelLabel.setText("Model: " + readModelId());
        cacheCountLabel.setText("Cache: " + translator.getCache().size()
                + " / " + translator.getCache().getMaxSize() + " entries");

        List<ChatCapture.TranslatedMessage> messages;
        synchronized (chatCapture.getTranslatedMessages()) {
            messages = List.copyOf(chatCapture.getTranslatedMessages());
        }

        if (!messages.isEmpty()) {
            int start = Math.max(0, messages.size() - 10);
            String logText = messages.subList(start, messages.size()).stream()
                    .map(msg -> String.format("[%s] %s\n  \u2192 %s",
                            msg.getSender(),
                            truncate(msg.getOriginal(), 40),
                            truncate(msg.getTranslation(), 40)))
                    .collect(Collectors.joining("\n---\n"));
            translationLog.setText(logText);
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    private static final String DEFAULT_MODEL = "Xenova/opus-mt-en-es";

    private static final java.util.Set<String> OBSOLETE_MODELS = java.util.Set.of(
            "google-t5/t5-small",
            "google-t5/t5-base",
            "facebook/nllb-200-distilled-600M",
            "Helsinki-NLP/opus-mt-en-es",
            "Helsinki-NLP/opus-mt-en-fr",
            "Helsinki-NLP/opus-mt-en-de"
    );

    private String readModelId() {
        String modelId = configManager.getConfiguration("runespeak", "modelId");
        if (modelId == null || modelId.isBlank() || OBSOLETE_MODELS.contains(modelId)) {
            return DEFAULT_MODEL;
        }
        return modelId;
    }

    private int readCacheSize() {
        String val = configManager.getConfiguration("runespeak", "cacheSize");
        return val != null ? Integer.parseInt(val) : 5000;
    }

    public void shutdown() {
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
    }
}
