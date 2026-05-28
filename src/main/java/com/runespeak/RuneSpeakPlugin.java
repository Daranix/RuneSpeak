package com.runespeak;

import com.google.inject.Provides;
import com.runespeak.capture.ChatCapture;
import com.runespeak.capture.DialogCapture;
import com.runespeak.capture.MenuCapture;
import com.runespeak.capture.OverlayTextCapture;
import com.runespeak.capture.WidgetTextScanner;
import com.runespeak.data.DataUploader;
import com.runespeak.data.TranslationDatabase;
import com.runespeak.overlay.TranslationOverlay;
import com.runespeak.panel.RuneSpeakPanel;
import com.runespeak.translate.LocalTranslator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@PluginDescriptor(
        name = "RuneSpeak",
        description = "Local AI translation plugin for OSRS using HuggingFace models.",
        tags = {"translation", "ai", "local", "offline", "huggingface"}
)
public class RuneSpeakPlugin extends Plugin {
    @Inject
    @Getter
    private Client client;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    @Getter
    private RuneSpeakConfig config;

    @Inject
    private LocalTranslator translator;

    @Inject
    private MenuCapture menuCapture;

    @Inject
    private DialogCapture dialogCapture;

    @Inject
    private ChatCapture chatCapture;

    @Inject
    private OverlayTextCapture overlayTextCapture;

    @Inject
    private WidgetTextScanner widgetTextScanner;

    @Inject
    private TranslationOverlay translationOverlay;

    @Inject
    private ConfigManager configManager;

    @Inject
    private TranslationDatabase translationDatabase;

    @Inject
    private DataUploader dataUploader;

    private RuneSpeakPanel panel;
    private NavigationButton navButton;

    private final Set<Integer> discoveredGroups = new HashSet<>();

    private static final Set<Integer> UI_GROUPS = Set.of(
            InterfaceID.INVENTORY,
            InterfaceID.EQUIPMENT,
            InterfaceID.EQUIPMENT_SIDE,
            InterfaceID.PRAYERBOOK,
            InterfaceID.QUICKPRAYER,
            InterfaceID.MAGIC_SPELLBOOK,
            InterfaceID.COMBAT_INTERFACE,
            InterfaceID.CHATBOX,
            InterfaceID.SETTINGS,
            InterfaceID.SETTINGS_SIDE,
            InterfaceID.STATS,
            InterfaceID.EMOTE,
            InterfaceID.MUSIC,
            InterfaceID.FRIENDS,
            InterfaceID.IGNORE,
            InterfaceID.LOGOUT,
            InterfaceID.WORLDMAP,
            InterfaceID.WORNITEMS,
            InterfaceID.ACCOUNT,
            InterfaceID.XP_DROPS,
            InterfaceID.SKILL_GUIDE,
            InterfaceID.SKILL_GUIDE_V2,
            InterfaceID.ORBS,
            InterfaceID.ORBS_NOMAP,
            InterfaceID.ORBS_OSM,
            InterfaceID.ORBS_OSM_NOMAP,
            InterfaceID.BUFF_BAR,
            InterfaceID.MINIGAMES,
            InterfaceID.GROUPING,
            InterfaceID.QUESTLIST,
            InterfaceID.QUESTJOURNAL,
            InterfaceID.KEYBINDING,
            InterfaceID.REPORTABUSE,
            InterfaceID.HISCORES,
            InterfaceID.SLAYER_REWARDS,
            InterfaceID.CLANS_INFO,
            InterfaceID.CLANS_SIDEPANEL,
            InterfaceID.CLANS_GUEST_SIDEPANEL,
            InterfaceID.CLANS_MEMBERS,
            InterfaceID.CLANS_HALL,
            InterfaceID.ACCOUNT_SUMMARY_SIDEPANEL,
            InterfaceID.WORLDSWITCHER,
            InterfaceID.MAKEOVER,
            InterfaceID.PLAYER_DESIGN,
            InterfaceID.ADVENTUREPATH,
            InterfaceID.ADVENTUREPATH_SIDE,
            InterfaceID.COLLECTION,
            InterfaceID.NOTIFICATION_DISPLAY,
            InterfaceID.LOADING_ICON,
            InterfaceID.TOPLEVEL,
            InterfaceID.TOPLEVEL_DISPLAY,
            InterfaceID.TOPLEVEL_OSRS_STRETCH,
            InterfaceID.TOPLEVEL_PRE_EOC,
            InterfaceID.TOPLEVEL_OSM,
            InterfaceID.TOPLEVEL_SPECTATOR,
            InterfaceID.SCREENFILTER,
            InterfaceID.SCREENHIGHLIGHT,
            InterfaceID.FADE_OVERLAY,
            InterfaceID.SMOKEOVERLAY,
            InterfaceID.PVP_ICONS,
            InterfaceID.POPUPOVERLAY,
            InterfaceID.FLOATER_BLANKMODAL,
            InterfaceID.REUSABLE_FLOATER,
            InterfaceID.GRAPHICBOX,
            InterfaceID.OVERLAY_PORTAL,
            InterfaceID.DEATHKEEP,
            InterfaceID.DEATH_OFFICE,
            InterfaceID.DEATH_COFFER,
            InterfaceID.DEATH_COFFER_SIDE,
            InterfaceID.LOOTTOOLS,
            InterfaceID.LOADING_ICON_MODAL,
            InterfaceID.GRAVESTONE_RETRIEVAL,
            InterfaceID.GRAVESTONE_GENERIC,
            InterfaceID.POH_LOADING,
            InterfaceID.POH_OPTIONS,
            InterfaceID.POH_ADD_ROOM,
            InterfaceID.POH_FURNITURE_CREATION,
            InterfaceID.POH_FURNITURE_CREATION_MENU,
            InterfaceID.POH_BOARD,
            InterfaceID.POH_BOOKCASE,
            InterfaceID.POH_MENAGERIE,
            InterfaceID.POH_PETLIST,
            InterfaceID.POH_RANGING,
            InterfaceID.POH_JEWELLERY_BOX,
            InterfaceID.POH_HANGMAN,
            InterfaceID.POH_VIEWER,
            InterfaceID.POH_TROPHY_SIDE,
            InterfaceID.POH_TROPHY_MENU,
            InterfaceID.POH_SCRYING_POOL,
            InterfaceID.POH_COSTUMES,
            InterfaceID.POH_COSTUMES_SIDE,
            InterfaceID.BANKMAIN,
            InterfaceID.BANKSIDE,
            InterfaceID.BANKPIN_KEYPAD,
            InterfaceID.BANKPIN_SETTINGS,
            InterfaceID.BANK_DEPOSITBOX,
            InterfaceID.BANK_DEPOSIT_IMP,
            InterfaceID.SHOPMAIN,
            InterfaceID.SHOPSIDE,
            InterfaceID.TRADEMAIN,
            InterfaceID.TRADESIDE,
            InterfaceID.TRADECONFIRM,
            InterfaceID.RUNE_POUCH,
            InterfaceID.CHATMENU,
            InterfaceID.MESSAGEBOX,
            InterfaceID.MESSAGEBOX_URL,
            InterfaceID.MESSAGEBOX_TITLED,
            InterfaceID.PM_CHAT,
            InterfaceID.CHAT_BOTH,
            InterfaceID.CHAT_LEFT,
            InterfaceID.CHAT_RIGHT,
            InterfaceID.CHATCHANNEL_CURRENT,
            InterfaceID.CHATCHANNEL_SETUP,
            InterfaceID.SIDE_CHANNELS,
            InterfaceID.SIDE_CHANNELS_LARGE,
            InterfaceID.QUESTDISPLAY,
            InterfaceID.QUESTSCROLL,
            InterfaceID.QUESTSCROLL_SPEEDRUN,
            InterfaceID.QUESTJOURNAL_OVERVIEW,
            InterfaceID.SKILLMULTI,
            InterfaceID.XPREWARD,
            InterfaceID.XPDROPS_SETUP,
            InterfaceID.GRAPHICAL_MULTI,
            InterfaceID.TEXTFIELD_CSV,
            InterfaceID.DISPLAYNAME,
            InterfaceID.TUTORIAL_DISPLAYNAME,
            InterfaceID.BOND_PROMPT,
            InterfaceID.BOND_MAIN,
            InterfaceID.BOND_CONVERT,
            InterfaceID.BOND_MANAGEMENT,
            InterfaceID.F2P_BOND_REDEEM,
            InterfaceID.MEMBERSHIP_BENEFITS,
            InterfaceID.MEMBERSHIP_BENEFITS_PROMPT,
            InterfaceID.ITEMSETS,
            InterfaceID.ITEMSETS_SIDE,
            InterfaceID.SMITHING,
            InterfaceID.SILVER_CRAFTING,
            InterfaceID.CRAFTING_GOLD,
            InterfaceID.FARMING_TOOLS,
            InterfaceID.FARMING_TOOLS_SIDE,
            InterfaceID.FARMING_VIEW,
            InterfaceID.AUTOCAST,
            InterfaceID.MAGICTRAINING_MAIN,
            InterfaceID.MAGICTRAINING_ALCHEM,
            InterfaceID.MAGICTRAINING_ENCHA,
            InterfaceID.MAGICTRAINING_GRAVE,
            InterfaceID.MAGICTRAINING_SHOP,
            InterfaceID.MAGICTRAINING_TELE,
            InterfaceID.SEED_VAULT,
            InterfaceID.SEED_VAULT_DEPOSIT,
            InterfaceID.PVP_ARENA_SIDEPANEL,
            InterfaceID.PVP_ARENA_SIDEOPTIONS,
            InterfaceID.PVP_ARENA_STAGINGAREA_HUD,
            InterfaceID.PVP_ARENA_STAGINGAREA_SUPPLIES,
            InterfaceID.PVP_ARENA_STAGINGAREA_SHARELOADOUT,
            InterfaceID.PVP_ARENA_HUD,
            InterfaceID.PVP_ARENA_CHOOSEBUILD,
            InterfaceID.PVP_ARENA_BOARD,
            InterfaceID.PVP_ARENA_BOARD_OPTIONS,
            InterfaceID.PVP_ARENA_LEGACYDUEL_OPTIONS,
            InterfaceID.PVP_ARENA_LEGACYDUEL_CONFIRM,
            InterfaceID.PVP_ARENA_UNRANKEDDUEL,
            InterfaceID.PVP_ARENA_APPLICANTS,
            InterfaceID.PVP_ARENA_REWARDS,
            InterfaceID.PVP_ARENA_SCOREBOARD,
            InterfaceID.PVP_ARENA_SPECTATOR,
            InterfaceID.PVP_ARENA_1V1_INFO,
            InterfaceID.PVP_ARENA_RUNEPOUCH,
            InterfaceID.CLANWARS_SETUP,
            InterfaceID.CLANWARS_CONFIRM,
            InterfaceID.CLANWARS_VIEW,
            InterfaceID.CLANWARS_FFA,
            InterfaceID.CLANWARS_HUD,
            InterfaceID.CLANWARS_GAMEOVER,
            InterfaceID.MENU,
            InterfaceID.MENU_NEW,
            InterfaceID.ADVENTUREPATH_REWARD,
            InterfaceID.HOTKEY_SETTINGS,
            InterfaceID.HOTKEY_SETTINGS_NEW,
            InterfaceID.OSM_HOTKEYS,
            InterfaceID.CLANS_BOARD,
            InterfaceID.CLANS_EVENTS,
            InterfaceID.CLANS_EVENTS_CREATE,
            InterfaceID.CLANS_PERMISSIONS,
            InterfaceID.CLANS_APPLICANTS,
            InterfaceID.CLANS_BANNED,
            InterfaceID.CLANS_INTERESTS,
            InterfaceID.CLANS_OUTFIT,
            InterfaceID.CLANS_RANKTITLES,
            InterfaceID.CLANS_STORAGE_MAIN,
            InterfaceID.CLANS_STORAGE_SIDE,
            InterfaceID.CLANS_CREATION_SIDEPANEL,
            InterfaceID.CLAN_PIANO,
            InterfaceID.PVP_STORE,
            InterfaceID.PVP_STORE_SIDE,
            InterfaceID.GE_OFFERS,
            InterfaceID.GE_OFFERS_SIDE,
            InterfaceID.GE_COLLECT,
            InterfaceID.GE_HISTORY,
            InterfaceID.GE_PRICELIST,
            InterfaceID.GE_PRICECHECKER,
            InterfaceID.GE_PRICECHECKER_SIDE,
            InterfaceID.GE_VIEWONLY,
            InterfaceID.GE_ITEMSINK_MONITOR,
            InterfaceID.SLAYER_REWARDS_TASK_LIST,
            InterfaceID.TARGET,
            InterfaceID.TARGET_STREAKS,
            InterfaceID.STAT_BOOSTS_HUD,
            InterfaceID.HPBAR_HUD,
            InterfaceID.NIGHTMARE_TOTEMS,
            InterfaceID.NIGHTMARE_SCOREBOARD,
            InterfaceID.INFERNO_HP_HUD,
            InterfaceID.GODWARS_OVERLAY,
            InterfaceID.MOTHERLODE_HUD,
            InterfaceID.BLAST_FURNACE_HUD,
            InterfaceID.FORESTRY_KIT_MAIN,
            InterfaceID.FORESTRY_KIT_SIDE,
            InterfaceID.HUNTSMANS_KIT,
            InterfaceID.HUNTSMANS_KIT_SIDE,
            InterfaceID.TACKLE_BOX_MAIN,
            InterfaceID.TACKLE_BOX_SIDE,
            InterfaceID.FOSSIL_STORAGE,
            InterfaceID.FOSSIL_STORAGE_INV,
            InterfaceID.GIM_SIDEPANEL,
            InterfaceID.GIM_CREATION_SIDEPANEL,
            InterfaceID.GIM_LIMITED_SIDEPANEL,
            InterfaceID.GIM_SHARED_BANK_UNLOCKS,
            InterfaceID.GIM_OPTIONS,
            InterfaceID.GIM_SETTINGS,
            InterfaceID.GIM_LEAVING,
            InterfaceID.SHARED_BANK,
            InterfaceID.SHARED_BANK_SIDE,
            InterfaceID.HELPER_COX,
            InterfaceID.HELPER_GENERIC,
            InterfaceID.EHC_RATING,
            InterfaceID.EHC_WORLDHOP,
            InterfaceID.LEAGUE_SIDE_PANEL,
            InterfaceID.LEAGUE_3_SIDE_PANEL,
            InterfaceID.LEAGUE_REWARDS,
            InterfaceID.LEAGUE_TASKS,
            InterfaceID.LEAGUE_TUTORIAL,
            InterfaceID.LEAGUE_TUTORIAL_MAIN,
            InterfaceID.LEAGUE_INFO,
            InterfaceID.LEAGUE_RELICS,
            InterfaceID.LEAGUE_RANKINGS,
            InterfaceID.LEAGUE_TROPHIES,
            InterfaceID.LEAGUE_STATISTICS,
            InterfaceID.LEAGUE_RANK,
            InterfaceID.LEAGUE_FIRSTS,
            InterfaceID.LEAGUE_SECONDINV,
            InterfaceID.LEAGUE_COMBAT_MASTERY,
            InterfaceID.LEAGUE_3_UNLOCKS,
            InterfaceID.LEAGUE_3_FRAGMENTS,
            InterfaceID.LEAGUE_3_SELECT_CLASS,
            InterfaceID.LEAGUE_SUMMARY,
            InterfaceID.LEAGUE_SKILLCAPES_SHOP,
            InterfaceID.LEAGUE_3_INTRO_CUTSCENE,
            InterfaceID.COLLECTION_OVERVIEW,
            InterfaceID.TALENT_TREE,
            InterfaceID.MM_OVERLAY,
            InterfaceID.MM_FORMULAS,
            InterfaceID.WORLDSWITCHER_FILTER,
            InterfaceID.WORLDSWITCHER_OPTIONS,
            InterfaceID.DEADMANPROTECT,
            InterfaceID.DEADMAN_SAFEBOX,
            InterfaceID.DEADMAN_SAFEBOX_SIDE,
            InterfaceID.DEADMAN_SP,
            InterfaceID.DEADMAN_SP_POINTS,
            InterfaceID.DEADMAN_SIGILS,
            InterfaceID.DEADMAN_DELAY,
            InterfaceID.DEADMAN_TUTORIAL,
            InterfaceID.DEADMANLOOT,
            InterfaceID.DEADMAN_TOURNAMENT_VIEWER,
            InterfaceID.DEADMAN_SPECTATOR,
            InterfaceID.XPTRACKER,
            InterfaceID.BUGREPORT,
            InterfaceID.MOBILE_RATING,
            InterfaceID.SAILING_SIDEPANEL,
            InterfaceID.SAILING_LOG,
            InterfaceID.SAILING_CREW,
            InterfaceID.SAILING_SPYGLASS,
            InterfaceID.SAILING_CUSTOMISATION,
            InterfaceID.SAILING_BOAT_SELECTION,
            InterfaceID.SAILING_BOAT_CARGOHOLD,
            InterfaceID.SAILING_BOAT_CARGOHOLD_SIDE,
            InterfaceID.SAILING_INTRO_HUD,
            InterfaceID.SAILING_MENU,
            InterfaceID.SAILING_BT_HUD,
            InterfaceID.SAILING_BT_STATISTICS,
            InterfaceID.SAILING_BT_SELECTION,
            InterfaceID.PORT_TASK_BOARD,
            InterfaceID.PORT_TASK_INFO,
            InterfaceID.PATCHY,
            InterfaceID.NPS,
            InterfaceID.NUMBER_PAD,
            InterfaceID.PREPOT_DEVICE,
            InterfaceID.PREPOT_DEVICE_DEBUG
    );

    @Override
    protected void startUp() {
        log.info("RuneSpeak starting up...");

        migrateConfigKeys();

        translator.applyConfig();

        overlayManager.add(translationOverlay);
        initPanel();

        Language source = config.getSourceLanguage();
        Language target = config.getTargetLanguage();
        translator.setLanguages(source, target);

        widgetTextScanner.register(InterfaceID.ObjectboxDouble.TEXT, "ObjectboxDouble.Text", RuneSpeakConfig::translateNpcDialogue);
        widgetTextScanner.register(InterfaceID.ObjectboxDouble.PAUSEBUTTON, "ObjectboxDouble.PauseButton", RuneSpeakConfig::translateNpcDialogue);
        widgetTextScanner.register(InterfaceID.Objectbox.TEXT, "Objectbox.Text", RuneSpeakConfig::translateNpcDialogue);

        translationDatabase.init(config);
        if (config.anonymousDataSubmission()) {
            dataUploader.start(config.getDataUploadUrl(), config.getDataUploadInterval());
        }

        startModelLoading();

        log.info("RuneSpeak started!");
    }

    @Override
    protected void shutDown() {
        log.info("RuneSpeak shutting down...");

        overlayManager.remove(translationOverlay);

        if (navButton != null) {
            clientToolbar.removeNavigation(navButton);
        }

        if (panel != null) {
            panel.shutdown();
        }

        dataUploader.stop();
        translator.shutdown();
        translationDatabase.shutdown();

        log.info("RuneSpeak stopped!");
    }

    @Provides
    RuneSpeakConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(RuneSpeakConfig.class);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING) {
            discoveredGroups.clear();
        }
        overlayTextCapture.onGameStateChanged(event);
        widgetTextScanner.onGameStateChanged(event);
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        if (client.getGameState() != GameState.LOGGED_IN) return;

        if (config.translateNpcDialogue()) {
            dialogCapture.checkAndTranslateDialog();
        }

        overlayTextCapture.checkAndTranslate();
        widgetTextScanner.onGameTick();
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        // Fires on hover (before right-click) — apply cached translations so the
        // hover tooltip shows translated text without needing a full right-click.
        if (client.getGameState() != GameState.LOGGED_IN) return;
        if (config.translateMenuEntries()) {
            menuCapture.handleMenuEntryAdded(event);
        }
    }

    @Subscribe
    public void onMenuOpened(MenuOpened event) {
        if (client.getGameState() != GameState.LOGGED_IN) return;
        if (config.translateMenuEntries()) {
            menuCapture.handleOpenedMenu(event);
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (client.getGameState() != GameState.LOGGED_IN) return;
        chatCapture.handleChatMessage(event);
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        int groupId = event.getGroupId();
        if (UI_GROUPS.contains(groupId)) return;
        if (!discoveredGroups.add(groupId)) return;

        Widget root = client.getWidget(groupId);
        if (root == null) return;

        List<Widget> textWidgets = new ArrayList<>();
        collectTextWidgets(root, textWidgets);

        if (textWidgets.isEmpty()) {
            log.debug("WidgetLoaded group={}: no text-bearing children found", groupId);
            return;
        }

        for (Widget w : textWidgets) {
            String name = String.format("Dynamic[%d:%d]", groupId, w.getIndex());
            widgetTextScanner.register(w.getId(), name, RuneSpeakConfig::translateNpcDialogue);
            log.info("Auto-registered text source: {} — '{}'", name, truncate(w.getText(), 60));
        }
    }

    private static void collectTextWidgets(Widget widget, List<Widget> results) {
        if (widget == null) return;

        String text = widget.getText();
        if (text != null && !text.isEmpty() && text.length() > 3) {
            results.add(widget);
        }

        Widget[] children = widget.getDynamicChildren();
        if (children != null) {
            for (Widget child : children) {
                collectTextWidgets(child, results);
            }
        }

        children = widget.getStaticChildren();
        if (children != null) {
            for (Widget child : children) {
                collectTextWidgets(child, results);
            }
        }

        children = widget.getNestedChildren();
        if (children != null) {
            for (Widget child : children) {
                collectTextWidgets(child, results);
            }
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    @Subscribe
    public void onOverheadTextChanged(OverheadTextChanged event) {
        if (!config.translateOverhead()) return;
        if (client.getGameState() != GameState.LOGGED_IN) return;

        String text = event.getOverheadText();
        translator.translateAsync(text).thenAccept(translated -> {
            if (!translated.equals(text) && !translated.startsWith("⏳")) {
                event.getActor().setOverheadText(translated);
                log.info("Overhead: {} -> {}", text, translated);
            }
        });
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!"runespeak".equals(event.getGroup())) return;

        switch (event.getKey()) {
            case "anonymousDataSubmission":
            case "dataUploadUrl":
            case "dataUploadInterval": {
                dataUploader.stop();
                if (config.anonymousDataSubmission()) {
                    dataUploader.start(config.getDataUploadUrl(), config.getDataUploadInterval());
                }
                break;
            }
            case "targetLanguage":
            case "sourceLanguage": {
                Language source = config.getSourceLanguage();
                Language target = config.getTargetLanguage();
                translator.setLanguages(source, target);
                log.info("Languages updated: {} → {}", source.getDisplayName(), target.getDisplayName());

                // Clear stale translations for the old language pair
                translator.getCache().clear();
                dialogCapture.clear();
                menuCapture.clear();
                chatCapture.clear();
                overlayTextCapture.clear();
                widgetTextScanner.clear();
                log.info("Translation cache and dialog state cleared for new language pair.");

                if (source != target) {
                    String modelId = "onnx-community/opus-mt-" + source.getTwoLetterCode() + "-" + target.getTwoLetterCode();
                    translator.initialize(modelId);
                }
                break;
            }
        }
    }

    private static final java.util.Set<String> OBSOLETE_MODELS = java.util.Set.of(
            "google-t5/t5-small",
            "google-t5/t5-base",
            "facebook/nllb-200-distilled-600M",
            "Helsinki-NLP/opus-mt-en-es",
            "Helsinki-NLP/opus-mt-en-fr",
            "Helsinki-NLP/opus-mt-en-de"
    );

    private static final String DEFAULT_MODEL = "onnx-community/opus-mt-en-es";

    private void migrateConfigKeys() {
        String oldVal = configManager.getConfiguration("runespeak", "translateTutorial");
        if (oldVal != null) {
            configManager.setConfiguration("runespeak", "translateOverlayText", oldVal);
            configManager.unsetConfiguration("runespeak", "translateTutorial");
            log.info("Migrated config key: translateTutorial -> translateOverlayText");
        }
    }

    private void startModelLoading() {
        Language source = config.getSourceLanguage();
        Language target = config.getTargetLanguage();
        if (source != target) {
            String modelId = "onnx-community/opus-mt-" + source.getTwoLetterCode() + "-" + target.getTwoLetterCode();
            log.info("startModelLoading: Loading model: {}", modelId);
            translator.initialize(modelId);
        }
        log.debug("startModelLoading: Initialization submitted to executor");
    }

    private void initPanel() {
        panel = injector.getInstance(RuneSpeakPanel.class);

        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");

        navButton = NavigationButton.builder()
                .tooltip("RuneSpeak")
                .icon(icon)
                .priority(6)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);
    }
}
