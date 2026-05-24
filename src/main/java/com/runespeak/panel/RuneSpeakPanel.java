package com.runespeak.panel;

import com.runespeak.Language;
import com.runespeak.RuneSpeakConfig;
import com.runespeak.capture.ChatCapture;
import com.runespeak.translate.LocalTranslator;
import com.runespeak.translate.ModelManager;
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
            "facebook/nllb-200-distilled-600M",
            "facebook/nllb-200-distilled-1.3B",
            "facebook/nllb-200-3.3B",
            "Helsinki-NLP/opus-mt-en-es",
            "Helsinki-NLP/opus-mt-en-fr",
            "Helsinki-NLP/opus-mt-en-de",
            "Helsinki-NLP/opus-mt-en-zh",
            "google-t5/t5-small",
            "google-t5/t5-base"
    };

    private JComboBox<Language> sourceCombo;
    private JComboBox<Language> targetCombo;
    private JComboBox<String> modelCombo;
    private JSpinner cacheSizeSpinner;
    private JTextField cacheDirField;
    private JTextField pythonPathField;
    private JButton applyButton;
    private JLabel depStatusLabel;
    private JButton checkDepsButton;

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
        addEnvironmentSection(scrollContent);
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

        modelLabel = new JLabel("Model: " + config.getModelId());
        modelLabel.setFont(new Font("Dialog", Font.PLAIN, 10));
        header.add(modelLabel, gbc);

        cacheCountLabel = new JLabel("Cache: 0 entries");
        cacheCountLabel.setFont(new Font("Dialog", Font.PLAIN, 10));
        header.add(cacheCountLabel, gbc);

        parent.add(header);
    }

    private void addEnvironmentSection(JPanel parent) {
        JPanel section = new JPanel(new GridBagLayout());
        section.setBorder(new TitledBorder("Python Environment"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(3, 6, 3, 6);
        gbc.gridwidth = GridBagConstraints.REMAINDER;

        JPanel pyRow = new JPanel(new BorderLayout(4, 0));
        pyRow.add(new JLabel("Python path:"), BorderLayout.WEST);
        pythonPathField = new JTextField();
        pythonPathField.setText(config.getPythonPath());
        pythonPathField.setToolTipText("Leave empty to auto-detect from PATH");
        pyRow.add(pythonPathField, BorderLayout.CENTER);
        section.add(pyRow, gbc);

        depStatusLabel = new JLabel("Dependencies: not checked");
        depStatusLabel.setFont(new Font("Dialog", Font.PLAIN, 10));
        section.add(depStatusLabel, gbc);

        JPanel depBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        checkDepsButton = new JButton("Check Dependencies");
        checkDepsButton.addActionListener(e -> runDependencyCheck());
        depBtnRow.add(checkDepsButton);

        JButton installHintBtn = new JButton("Show Install Guide");
        installHintBtn.addActionListener(e -> showInstallGuide());
        depBtnRow.add(installHintBtn);

        section.add(depBtnRow, gbc);

        parent.add(section);
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

        JPanel langRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        langRow.add(new JLabel("From:"));
        sourceCombo = new JComboBox<>(languages);
        sourceCombo.setSelectedItem(config.getSourceLanguage());
        sourceCombo.setPreferredSize(new Dimension(150, 24));
        langRow.add(sourceCombo);
        langRow.add(new JLabel("  To:"));
        targetCombo = new JComboBox<>(languages);
        targetCombo.setSelectedItem(config.getTargetLanguage());
        targetCombo.setPreferredSize(new Dimension(150, 24));
        langRow.add(targetCombo);
        section.add(langRow, gbc);

        JPanel modelRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        modelRow.add(new JLabel("Model:"));
        modelCombo = new JComboBox<>(MODEL_PRESETS);
        modelCombo.setEditable(true);
        String currentModel = config.getModelId();
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
        modelCombo.setPreferredSize(new Dimension(250, 24));
        modelRow.add(modelCombo);
        section.add(modelRow, gbc);

        JPanel cacheRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        cacheRow.add(new JLabel("Max cache:"));
        cacheSizeSpinner = new JSpinner(new SpinnerNumberModel(config.getCacheSize(), 100, 100_000, 500));
        cacheSizeSpinner.setPreferredSize(new Dimension(100, 24));
        cacheRow.add(cacheSizeSpinner);
        cacheRow.add(new JLabel(" entries"));
        section.add(cacheRow, gbc);

        JPanel dirLabelRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        dirLabelRow.add(new JLabel("Cache dir:"));
        section.add(dirLabelRow, gbc);

        JPanel dirRow = new JPanel(new BorderLayout(4, 0));
        cacheDirField = new JTextField();
        Path currentDir = translator.getCacheDir();
        cacheDirField.setText(currentDir.toAbsolutePath().toString());
        cacheDirField.setPreferredSize(new Dimension(200, 24));
        dirRow.add(cacheDirField, BorderLayout.CENTER);

        JButton browseButton = new JButton("Browse...");
        browseButton.addActionListener(e -> chooseCacheDir());
        dirRow.add(browseButton, BorderLayout.EAST);
        section.add(dirRow, gbc);

        JPanel applyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        applyButton = new JButton("Apply Settings");
        applyButton.addActionListener(e -> applySettings());
        applyRow.add(applyButton);

        JButton refreshCacheBtn = new JButton("Flush Cache");
        refreshCacheBtn.setToolTipText("Write in-memory cache to disk now");
        refreshCacheBtn.addActionListener(e -> {
            translator.getCache().flushNow();
            refreshStatus();
        });
        applyRow.add(refreshCacheBtn);

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
        scrollPane.setPreferredSize(new Dimension(250, 200));
        section.add(scrollPane, BorderLayout.CENTER);

        parent.add(section);
    }

    private void addButtonPanel() {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));

        JButton clearButton = new JButton("Clear Log");
        clearButton.addActionListener(e -> {
            translationLog.setText("");
            chatCapture.clear();
        });
        buttonPanel.add(clearButton);

        JButton reloadButton = new JButton("Reload Model");
        reloadButton.addActionListener(e -> {
            reloadButton.setEnabled(false);
            statusLabel.setText("Status: Loading model...");
            translator.initialize(config.getModelId());
        });
        buttonPanel.add(reloadButton);

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

    private void runDependencyCheck() {
        checkDepsButton.setEnabled(false);
        checkDepsButton.setText("Checking...");
        depStatusLabel.setText("Python: checking...");

        SwingWorker<ModelManager.DependencyResult, Void> worker = new SwingWorker<>() {
            @Override
            protected ModelManager.DependencyResult doInBackground() {
                return translator.checkDependencies();
            }

            @Override
            protected void done() {
                try {
                    ModelManager.DependencyResult result = get();
                    if (result.isAllMet()) {
                        depStatusLabel.setText("Python: " + result.getPythonVersion()
                                + " — " + result.getStatusMessage());
                        statusLabel.setText("Status: Dependencies OK — "
                                + (result.isCudaAvailable() ? "CUDA ready" : "CPU mode"));
                    } else {
                        depStatusLabel.setText("Python: " + result.getPythonVersion()
                                + " — " + result.getStatusMessage());
                        statusLabel.setText("Status: Missing Python packages — click Show Install Guide");
                    }
                } catch (Exception ex) {
                    depStatusLabel.setText("Dependencies: check failed (" + ex.getMessage() + ")");
                    statusLabel.setText("Status: Dep check error");
                } finally {
                    checkDepsButton.setEnabled(true);
                    checkDepsButton.setText("Check Dependencies");
                }
            }
        };
        worker.execute();
    }

    private void showInstallGuide() {
        String guide = "Python Dependencies Installation Guide\n"
                + "=====================================\n\n"
                + "1. Ensure Python 3.8+ is installed and on PATH\n"
                + "   (or set a custom path in the field above)\n\n"
                + "2. Install required packages:\n\n"
                + "   pip install torch transformers sentencepiece protobuf accelerate\n\n"
                + "   For CUDA (GPU) support on Windows:\n"
                + "   pip install torch --index-url https://download.pytorch.org/whl/cu121\n\n"
                + "3. Click 'Check Dependencies' to verify\n\n"
                + "4. Click 'Apply Settings' then 'Reload Model' to start\n\n"
                + "Tip: First model download (~1.2GB) happens on first load.\n"
                + "     Subsequent loads use the local cache.";
        JTextArea textArea = new JTextArea(guide);
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        textArea.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(textArea),
                "Install Guide", JOptionPane.INFORMATION_MESSAGE);
    }

    private void applySettings() {
        Language src = (Language) sourceCombo.getSelectedItem();
        Language tgt = (Language) targetCombo.getSelectedItem();
        translator.setLanguages(src, tgt);

        String pyPath = pythonPathField.getText().trim();
        configManager.setConfiguration("runespeak", "pythonPath", pyPath);
        translator.applyConfig();

        int newSize = (Integer) cacheSizeSpinner.getValue();
        translator.getCache().setMaxSize(newSize);

        String dir = cacheDirField.getText().trim();
        if (!dir.isEmpty()) {
            log.info("Cache directory set to: {}", dir);
        }

        String selectedModel = (String) modelCombo.getSelectedItem();
        boolean modelChanged = !selectedModel.equals(config.getModelId());
        if (modelChanged) {
            configManager.setConfiguration("runespeak", "modelId", selectedModel);
            statusLabel.setText("Status: Loading model...");
            translator.initialize(selectedModel);
        }

        log.info("Settings applied — {} → {}, model: {}, cache: {} max, py: {}",
                src.getDisplayName(), tgt.getDisplayName(), selectedModel, newSize, pyPath.isEmpty() ? "auto" : pyPath);
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
            ModelManager.DependencyStatus ds = translator.getDependencyStatus();
            String device = ds.isCudaAvailable() ? "CUDA" : "CPU";
            statusLabel.setText("Status: Ready (" + device + ")");
        } else if (translator.isLoading()) {
            statusLabel.setText("Status: Loading model (first run may download ~1.2GB)...");
        } else {
            ModelManager.DependencyStatus ds = translator.getDependencyStatus();
            if (ds.getPythonVersion() != null && !ds.getPythonVersion().isEmpty()) {
                statusLabel.setText("Status: " + ds.getStatusMessage());
            } else {
                statusLabel.setText("Status: Waiting — model not loaded");
            }
        }

        modelLabel.setText("Model: " + config.getModelId());
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

    public void shutdown() {
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
    }
}
