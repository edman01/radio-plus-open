package fi.radioplus.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint({"SetTextI18n", "ClickableViewAccessibility"})
public final class MainActivity extends Activity implements
        RadioServiceClient.Listener,
        FavoriteAdapter.Listener {

    private static final int MAX_STATION_NAME_LENGTH = 40;
    private static final long AUTO_SCAN_START_GRACE_MS = 5000L;
    private static final long AUTO_SCAN_MAX_MS = 90_000L;
    private static final long AUTO_SCAN_STOP_GRACE_MS = 2500L;
    private static final int FAVORITES_PER_PAGE = 6;
    private static final int REQUEST_STATION_LOGO = 701;
    private static final int REQUEST_PLAYBACK_NOTIFICATIONS = 702;
    private static final long TUNE_SELECTION_GRACE_MS = 2500L;
    private static final long UNNAMED_STATION_PROMPT_DELAY_MS = 1800L;
    private static final long PAGE_EXIT_ANIMATION_MS = 85L;
    private static final long PAGE_ENTER_ANIMATION_MS = 175L;
    private static final long STATION_REORDER_HOLD_MS = 3000L;
    private static final long STATION_REORDER_PAGE_DELAY_MS = 550L;
    private static final long MANUAL_SEEK_CAPTURE_TIMEOUT_MS = 15_000L;
    private static final String PROJECT_URL = "https://github.com/edman01/radio-plus-open";

    private final ArrayList<View> radioControls = new ArrayList<>();
    private final ArrayList<FavoriteStation> favoriteStations = new ArrayList<>();
    private final ArrayList<FavoriteStation> catalogStations = new ArrayList<>();
    private final Map<Integer, String> observedScanNames = new LinkedHashMap<>();
    private final LinkedHashSet<Integer> observedScanFrequencies = new LinkedHashSet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService logoExecutor = Executors.newSingleThreadExecutor();

    private TextView connectionText;
    private TextView bandText;
    private TextView frequencyText;
    private TextView unitText;
    private TextView stationText;
    private TextView radioText;
    private TextView rdsStatusText;
    private TextView stereoText;
    private TextView receptionText;
    private TextView activityText;
    private TextView stationCountText;
    private TextView scanSummaryText;
    private TextView emptyFavoritesText;
    private View connectionDot;
    private EditText manualFrequency;
    private AlertDialog manualTuningDialog;
    private RadioSettingsDialog settingsDialog;
    private AlertDialog aboutDialog;
    private SteeringDiagnosticsDialog steeringDiagnosticsDialog;
    private EditText manualTuningInput;
    private TextView manualTuningStatus;
    private int manualTuningBand = -1;
    private int manualTuningRequestedFrequency = -1;
    private int manualFmBand;
    private int manualFmFrequency = 101_700;
    private int manualAmFrequency = 999;
    private RadioButton manualFmChoice;
    private RadioButton manualAmChoice;
    private RadioButton manualLocalChoice;
    private RadioButton manualDxChoice;
    private boolean manualChoiceUiUpdating;
    private boolean manualTunePerformed;
    private int pendingManualCatalogBand = -1;
    private int pendingManualCatalogFrequency = -1;
    private String pendingManualSeekOriginKey = "";
    private long pendingManualSeekAt;
    private Button autoScanButton;
    private Button bandButton;
    private Button stationsButton;
    private Button savedButton;
    private ProgressBar scanProgress;
    private TunerBackdropView tunerBackdrop;
    private GridView favoritesList;
    private LinearLayout favoritePageIndicator;

    private FavoriteStore favoriteStore;
    private StationStore stationStore;
    private StationNavigationStore stationNavigationStore;
    private FavoriteAdapter favoriteAdapter;
    private RadioServiceClient radioClient;
    private RadioServiceClient.RadioState currentState;

    private boolean autoScanRequested;
    private boolean autoScanObserved;
    private boolean autoScanStopRequested;
    private boolean autoScanFinalizing;
    private long autoScanRequestedAt;
    private boolean debugPreview;
    private boolean destroyed;
    private boolean activityStarted;
    private boolean radioConnected;
    private boolean showingFavorites = true;
    private boolean stationNameResolutionActive;
    private int[] stationNameFrequencies = new int[0];
    private int stationNameBand;
    private int stationNameIndex;
    private int stationNameAttempt;
    private long stationNameTuneRequestedAt;
    private long stationNameMatchedAt;
    private int stationNameReturnBand = -1;
    private int stationNameReturnFrequency = -1;
    private int completedScanFound;
    private int completedScanAdded;
    private boolean completedScanVendorAvailable;
    private int favoritePage;
    private boolean favoritePageManuallySelected;
    private int renderedFavoritePage = -1;
    private int renderedFavoritePageCount = -1;
    private String renderedFavoriteKey = "";
    private String activeFavoriteKey = "";
    private String manualTuneKey = "";
    private String pendingTuneKey = "";
    private long pendingTuneAt;
    private boolean playbackPausedByStationTap;
    private int pendingLogoBand = -1;
    private int pendingLogoFrequency = -1;
    private float favoriteTouchDownX;
    private float favoriteTouchDownY;
    private long favoriteTouchDownAt;
    private boolean favoriteTouchDragging;
    private boolean favoriteOptionsShown;
    private boolean favoritePageAnimating;
    private boolean favoriteReordering;
    private int favoriteTouchDownPosition = GridView.INVALID_POSITION;
    private int favoriteReorderIndex = -1;
    private long favoriteReorderPageChangedAt;
    private Runnable pendingFavoriteReorder;
    private Runnable pendingFavoriteOptions;
    private Runnable pendingUnnamedStationPrompt;

    private final Runnable autoScanStartTimeoutTask = this::handleAutoScanStartTimeout;
    private final Runnable autoScanHardTimeoutTask = this::handleAutoScanHardTimeout;
    private final Runnable autoScanStopTimeoutTask = this::handleAutoScanStopTimeout;
    private final Runnable stationNameCaptureTask = this::captureResolvedStationName;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLanguage.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        configureSystemBars();
        setContentView(R.layout.activity_main);
        showSystemBars();
        configureAdaptiveLayout();

        debugPreview = BuildConfig.DEBUG && getIntent().getBooleanExtra("preview", false);
        favoriteStore = new FavoriteStore(this);
        stationStore = new StationStore(this);
        migrateLegacyStationStorage();
        stationNavigationStore = new StationNavigationStore(this);
        showingFavorites = stationNavigationStore.favoritesSelected();
        radioClient = new RadioServiceClient(this, this);
        bindViews();
        configureControls();
        if (debugPreview) {
            seedDebugPreview();
        }
        refreshFavorites();
    }

    private void migrateLegacyStationStorage() {
        android.content.SharedPreferences migrations = getSharedPreferences(
                "radio_plus_migrations",
                MODE_PRIVATE
        );
        String key = "split_station_catalog_and_favorites_095";
        if (migrations.getBoolean(key, false)) {
            return;
        }
        List<FavoriteStation> legacyStations = favoriteStore.load();
        stationStore.mergeLegacy(legacyStations);
        favoriteStore.clear();
        migrations.edit().putBoolean(key, true).apply();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        if (debugPreview) {
            showDebugPreview();
        } else {
            requestPlaybackNotificationPermission();
            playbackPausedByStationTap = false;
            try {
                RadioPlaybackService.ensureRunningForUi(this);
            } catch (RuntimeException exception) {
                android.util.Log.w(
                        "JunsunRadioPlus",
                        "Background playback service could not be started",
                        exception
                );
            }
            radioClient.bind();
        }
    }

    private void requestPlaybackNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        android.content.SharedPreferences preferences = getSharedPreferences(
                "radio_plus_permissions",
                MODE_PRIVATE
        );
        if (preferences.getBoolean("notifications_requested", false)) {
            return;
        }
        preferences.edit().putBoolean("notifications_requested", true).apply();
        requestPermissions(
                new String[]{"android.permission.POST_NOTIFICATIONS"},
                REQUEST_PLAYBACK_NOTIFICATIONS
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        SteeringKeyService.setRadioVisible(!debugPreview);
    }

    @Override
    protected void onPause() {
        SteeringKeyService.setRadioVisible(false);
        RadioPlaybackService.noteUiBackgrounded();
        SteeringDiagnosticTrace.get().stop();
        super.onPause();
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        mainHandler.removeCallbacks(stationNameCaptureTask);
        if (favoriteReordering) {
            finishFavoriteReorder(false);
        } else {
            cancelPendingFavoriteReorder();
        }
        cancelPendingFavoriteOptions();
        if (!debugPreview) {
            radioConnected = false;
            setRadioControlsEnabled(false);
            radioClient.unbind();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (settingsDialog != null) {
            settingsDialog.dismiss();
        }
        if (aboutDialog != null) {
            aboutDialog.dismiss();
            aboutDialog = null;
        }
        if (steeringDiagnosticsDialog != null) steeringDiagnosticsDialog.dismiss();
        mainHandler.removeCallbacksAndMessages(null);
        if (radioClient != null) {
            radioClient.close();
        }
        logoExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_STATION_LOGO) {
            return;
        }
        int band = pendingLogoBand;
        int frequency = pendingLogoFrequency;
        pendingLogoBand = -1;
        pendingLogoFrequency = -1;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri logoUri = data.getData();
        FavoriteStation station = findKnownStation(band, frequency);
        if (station == null) {
            return;
        }
        logoExecutor.execute(() -> {
            try {
                String token = FavoriteLogoStore.importLogo(this, station, logoUri);
                mainHandler.post(() -> {
                    if (destroyed) {
                        FavoriteLogoStore.delete(this, token);
                        return;
                    }
                    FavoriteStation latest = findKnownStation(band, frequency);
                    if (latest == null) {
                        FavoriteLogoStore.delete(this, token);
                        return;
                    }
                    saveStationMetadata(latest.withLogo(token));
                    refreshFavorites();
                    toast(tr("Oma logo tallennettu", "Custom logo saved"));
                });
            } catch (Exception exception) {
                android.util.Log.w(
                        "JunsunRadioPlus",
                        "Station logo import failed",
                        exception
                );
                mainHandler.post(() -> {
                    if (!destroyed) {
                        toast(tr(
                                "Kuvaa ei voitu käyttää logona",
                                "The image could not be used as a logo"
                        ));
                    }
                });
            }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null) SteeringDiagnosticTrace.get().key("activity", event.getKeyCode(),
                event.getAction(), event.getMetaState(), event.getRepeatCount(),
                event.getScanCode(), event.getFlags(), event.getSource(), event.getDownTime(), event.getEventTime());
        if (!debugPreview
                && event != null
                && RadioPlaybackService.supportsMediaKey(event.getKeyCode())) {
            if (RadioMediaButtonReceiver.dispatch(this, event)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void configureSystemBars() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(getColor(R.color.background));
        getWindow().setNavigationBarColor(getColor(R.color.background));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(true);
        }
    }

    private void showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow()
                    .getDecorView()
                    .getWindowInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.systemBars());
                controller.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                );
            }
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            showSystemBars();
            if (favoritesList != null && favoriteAdapter != null) {
                scheduleFavoriteGridLayout(favoriteAdapter.getCount());
            }
        }
    }

    private void configureAdaptiveLayout() {
        LinearLayout root = findViewById(R.id.app_root);
        // A car display always uses the same glanceable vertical hierarchy:
        // now playing, station presets, then the persistent control bar.
        root.setOrientation(LinearLayout.VERTICAL);
    }

    private void bindViews() {
        connectionText = findViewById(R.id.connection_status);
        bandText = findViewById(R.id.band_text);
        frequencyText = findViewById(R.id.frequency_text);
        unitText = findViewById(R.id.frequency_unit);
        stationText = findViewById(R.id.station_text);
        radioText = findViewById(R.id.radio_text);
        rdsStatusText = findViewById(R.id.rds_status_text);
        stereoText = findViewById(R.id.stereo_text);
        receptionText = findViewById(R.id.reception_text);
        activityText = findViewById(R.id.activity_text);
        stationCountText = findViewById(R.id.station_count);
        scanSummaryText = findViewById(R.id.scan_summary);
        emptyFavoritesText = findViewById(R.id.empty_favorites);
        connectionDot = findViewById(R.id.connection_dot);
        manualFrequency = findViewById(R.id.manual_frequency);
        autoScanButton = findViewById(R.id.auto_scan_button);
        bandButton = findViewById(R.id.band_button);
        stationsButton = findViewById(R.id.stations_button);
        savedButton = findViewById(R.id.saved_button);
        scanProgress = findViewById(R.id.scan_progress);
        tunerBackdrop = findViewById(R.id.tuner_backdrop);
        updateBandButton(0);

        radioText.setSelected(true);
        stationText.setSelected(true);

        favoritesList = findViewById(R.id.favorites_list);
        favoritePageIndicator = findViewById(R.id.station_page_indicator);
        favoriteAdapter = new FavoriteAdapter(this, this);
        favoritesList.setAdapter(favoriteAdapter);
        favoritesList.setOnTouchListener(this::handleFavoritePageTouch);

        int[] controlIds = {
                R.id.band_button,
                R.id.seek_down_button,
                R.id.seek_up_button,
                R.id.step_down_button,
                R.id.step_up_button,
                R.id.scan_button,
                R.id.auto_scan_button,
                R.id.local_button,
                R.id.play_button,
                R.id.tune_button,
                R.id.manual_menu_button,
                R.id.stations_button,
                R.id.saved_button,
                R.id.more_button
        };
        for (int id : controlIds) {
            radioControls.add(findViewById(id));
        }
        setRadioControlsEnabled(false);
    }

    private void configureControls() {
        findViewById(R.id.band_button).setOnClickListener(view ->
                runRadio(remote -> remote.onBandEvent()));
        findViewById(R.id.seek_down_button).setOnClickListener(view -> seekLowerFrequency());
        findViewById(R.id.seek_up_button).setOnClickListener(view -> seekHigherFrequency());
        findViewById(R.id.step_down_button).setOnClickListener(view -> stepLowerFrequency());
        findViewById(R.id.step_up_button).setOnClickListener(view -> stepHigherFrequency());
        findViewById(R.id.scan_button).setOnClickListener(view ->
                runRadio(remote -> remote.onScanEvent()));
        findViewById(R.id.local_button).setOnClickListener(view ->
                runRadio(remote -> remote.onLocDxEvent()));
        findViewById(R.id.play_button).setOnClickListener(view -> {
            playbackPausedByStationTap = false;
            RadioPlaybackService.ensureRunning(this);
            if (currentState != null) {
                showActiveFavorite(
                        currentState.band + ":" + currentState.frequency,
                        true
                );
            }
        });
        findViewById(R.id.tune_button).setOnClickListener(view -> tuneManualFrequency());
        stationsButton.setOnClickListener(view -> showStationCatalog(false));
        savedButton.setOnClickListener(view -> showStationCatalog(true));
        findViewById(R.id.manual_menu_button).setOnClickListener(view ->
                showManualTuningDialog());
        findViewById(R.id.more_button).setOnClickListener(view -> showMoreControlsDialog());
        manualFrequency.setOnEditorActionListener((view, actionId, event) -> {
            tuneManualFrequency();
            return true;
        });
        autoScanButton.setOnClickListener(view -> showStationActionsDialog());
    }

    private void seekLowerFrequency() {
        manualTuneKey = "";
        tuneAdjacentStation(false);
    }

    private void seekHigherFrequency() {
        manualTuneKey = "";
        tuneAdjacentStation(true);
    }

    private void seekTunerFrequency(boolean higher) {
        // Manual tuning always controls the tuner, regardless of the list
        // visible behind its dialog (including an empty favorites list).
        manualTuneKey = "";
        if (debugPreview) {
            simulateDebugFrequency(higher);
            return;
        }
        // These OEM command names use the reverse of the displayed direction.
        runRadio(remote -> {
            if (higher) {
                remote.onSeekDownEvent();
            } else {
                remote.onSeekUpEvent();
            }
        });
    }

    private boolean tuneAdjacentStation(boolean next) {
        List<FavoriteStation> stations = stationNavigationStore.load();
        if (stations.isEmpty()) {
            toast(getString(showingFavorites ? R.string.favorites_empty : R.string.stations_empty));
            return true;
        }
        clearPendingStationSelection();
        if (!debugPreview) {
            // Use the same serialized, hardware-confirmed path as steering controls.
            RadioPlaybackService.skipStation(this, next);
            return true;
        }
        int band = currentState == null ? -1 : currentState.band;
        int frequency = currentState == null ? -1 : currentState.frequency;
        FavoriteStation target = FavoriteNavigator.selectInStoredOrder(
                stations,
                band,
                frequency,
                next
        );
        if (target == null) {
            return true;
        }
        // A one-item list has no adjacent station. Do not feed the currently
        // playing tile back through its tap-to-pause behavior.
        if (stations.size() == 1
                && target.band == band
                && target.frequency == frequency
                && (debugPreview
                ? !playbackPausedByStationTap
                : RadioPlaybackService.isPlaybackRequested())) {
            return true;
        }
        onTune(target);
        return true;
    }

    private void clearPendingStationSelection() {
        pendingTuneKey = "";
        pendingTuneAt = 0L;
        favoritePageManuallySelected = false;
        playbackPausedByStationTap = false;
        if (pendingUnnamedStationPrompt != null) {
            mainHandler.removeCallbacks(pendingUnnamedStationPrompt);
            pendingUnnamedStationPrompt = null;
        }
    }

    private void stepLowerFrequency() {
        manualTuneKey = "";
        if (debugPreview) {
            simulateDebugFrequency(false);
            return;
        }
        runRadio(remote -> remote.onManualUpEvent());
    }

    private void stepHigherFrequency() {
        manualTuneKey = "";
        if (debugPreview) {
            simulateDebugFrequency(true);
            return;
        }
        runRadio(remote -> remote.onManualDownEvent());
    }

    private void simulateDebugFrequency(boolean higher) {
        if (currentState == null) {
            return;
        }
        boolean fm = currentState.band < 3;
        int step = fm ? FrequencyRules.FM_STEP : FrequencyRules.AM_STEP;
        int minimum = fm ? FrequencyRules.FM_MIN : FrequencyRules.AM_MIN;
        int maximum = fm ? FrequencyRules.FM_MAX : FrequencyRules.AM_MAX;
        int next = currentState.frequency + (higher ? step : -step);
        if (next > maximum) {
            next = minimum;
        } else if (next < minimum) {
            next = maximum;
        }
        FavoriteStation known = findKnownStation(currentState.band, next);
        onStateChanged(new RadioServiceClient.RadioState(
                currentState.band,
                next,
                known == null ? "" : known.name,
                "",
                "",
                known != null && !known.name.isEmpty(),
                currentState.stereo,
                currentState.localMode,
                false,
                false,
                false,
                currentState.oemFavorite
        ));
    }

    private void showMoreControlsDialog() {
        if (settingsDialog != null && settingsDialog.isShowing()) {
            return;
        }
        settingsDialog = new RadioSettingsDialog(this,
                currentState == null ? null : currentState.localMode,
                new RadioSettingsDialog.Listener() {
                    @Override public void onLanguage() { showLanguageDialog(); }
                    @Override public void onSensitivity() { showReceptionModeDialog(); }
                    @Override public void onSteeringKeys() { showSteeringKeySetup(); }
                    @Override public void onAbout() { showAboutDialog(); }
                    @Override public void onAutoStartChanged(boolean enabled) {
                        setAutoStartEnabled(enabled);
                    }
                });
        settingsDialog.show();
    }

    private void showAboutDialog() {
        if (destroyed || isFinishing() || (aboutDialog != null && aboutDialog.isShowing())) return;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_version, BuildConfig.VERSION_NAME)
                        + "\n\n" + PROJECT_URL + "\n\n" + RadioApiFactory.description(this))
                .setPositiveButton(R.string.about_open_github, null)
                .setNegativeButton(R.string.settings_back, null)
                .create();
        aboutDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (aboutDialog == dialog) aboutDialog = null;
        });
        dialog.show();
        styleCarDialog(dialog);
        // Set the listener after show so a missing/blocked browser does not
        // automatically close the version and project information.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)));
                dialog.dismiss();
            } catch (ActivityNotFoundException | SecurityException error) {
                toast(getString(R.string.about_browser_unavailable));
            }
        });
    }

    private void showSteeringKeySetup() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_steering)
                .setMessage(getString(R.string.steering_automatic_hint) + "\n\n"
                        + getString(R.string.steering_permission_description))
                .setPositiveButton(R.string.steering_open_settings, (ignored, which) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (RuntimeException error) {
                        toast(getString(R.string.steering_settings_unavailable));
                    }
                })
                .setNeutralButton(getString(R.string.steering_test_buttons), (ignored, which) ->
                        showSteeringDiagnostics())
                .setNegativeButton(R.string.settings_back, null).create();
        dialog.show();
        styleCarDialog(dialog);
    }

    private void showSteeringDiagnostics() {
        if (steeringDiagnosticsDialog != null && steeringDiagnosticsDialog.isShowing()) return;
        SteeringDiagnosticsDialog dialog = new SteeringDiagnosticsDialog(this, debugPreview);
        steeringDiagnosticsDialog = dialog;
        dialog.setOnClosed(() -> {
            if (steeringDiagnosticsDialog == dialog) steeringDiagnosticsDialog = null;
        });
        dialog.show();
    }

    private void showReceptionModeDialog() {
        if (!RadioApiFactory.selectedSupportsLocalMode()) {
            showUnverifiedBackendFeature();
            return;
        }
        int selected = currentState != null && currentState.localMode ? 0 : 1;
        String[] modes = {
                tr("LOCAL vain vahvat asemat", "LOCAL strong stations only"),
                tr("DX myös heikot asemat", "DX weak stations too")
        };
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(tr("Hakuherkkyys", "Scan sensitivity"))
                .setSingleChoiceItems(modes, selected, (choiceDialog, which) -> {
                    setReceptionMode(which == 0);
                    choiceDialog.dismiss();
                })
                .setNegativeButton(R.string.settings_back, null)
                .create();
        dialog.show();
        styleCarDialog(dialog);
    }

    private void setAutoStartEnabled(boolean enabled) {
        AutoStartPreferences.setEnabled(this, enabled);
        toast(enabled
                ? tr(
                        "Automaattikäynnistys päällä. Valitse Junsunin Startup application -asetukseksi Empty.",
                        "Auto-start enabled. Set Junsun Startup application to Empty."
                )
                : tr("Automaattikäynnistys pois päältä", "Auto-start disabled"));
    }

    private void showLanguageDialog() {
        String[] languages = AppLanguage.names();
        String[] codes = AppLanguage.codes();
        int selected = AppLanguage.selectedIndex(this);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(tr("Kieli", "Language"))
                .setSingleChoiceItems(languages, selected, (choiceDialog, which) -> {
                    String language = codes[which];
                    boolean changed = AppLanguage.set(this, language);
                    choiceDialog.dismiss();
                    if (changed) {
                        StationWidgetProvider.updateAll(this);
                        RadioPlaybackService.refreshLocalizedText();
                        recreate();
                    }
                })
                .setNegativeButton(R.string.settings_back, null)
                .create();
        dialog.show();
        styleCarDialog(dialog);
    }


    private void showCarDialog(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialog.show();
        styleCarDialog(dialog);
    }

    private void styleCarDialog(AlertDialog dialog) {
        int titleId = getResources().getIdentifier("alertTitle", "id", "android");
        if (titleId != 0) {
            TextView title = dialog.findViewById(titleId);
            if (title != null) {
                title.setTextSize(26f);
            }
        }
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setTextSize(21f);
            message.setLineSpacing(0f, 1.12f);
        }
        ListView list = dialog.getListView();
        if (list != null) {
            list.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    styleCarDialogListItem(child);
                }

                @Override
                public void onChildViewRemoved(View parent, View child) {
                    // Nothing to restore: recycled rows keep the car-friendly sizing.
                }
            });
            for (int index = 0; index < list.getChildCount(); index++) {
                styleCarDialogListItem(list.getChildAt(index));
            }
            if (list.getAdapter() != null) {
                ViewGroup.LayoutParams params = list.getLayoutParams();
                params.height = Math.min(
                        dp(372),
                        list.getAdapter().getCount() * dp(62)
                );
                list.setLayoutParams(params);
            }
        }
        int[] buttonIds = {
                AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_NEUTRAL,
                AlertDialog.BUTTON_POSITIVE
        };
        for (int buttonId : buttonIds) {
            Button button = dialog.getButton(buttonId);
            if (button != null) {
                button.setAllCaps(false);
                button.setTextSize(19f);
                button.setMinHeight(dp(56));
            }
        }
    }

    private void styleCarDialogListItem(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextSize(21f);
            text.setMinHeight(dp(62));
            text.setGravity(Gravity.CENTER_VERTICAL);
        }
    }

    private void showStationActionsDialog() {
        if (autoScanFinalizing) {
            toast(tr("Viimeistellään asemalistaa…", "Finalizing station list…"));
            return;
        }
        if (autoScanStopRequested) {
            toast(tr("Automaattihakua pysäytetään…", "Stopping automatic scan…"));
            return;
        }
        if (autoScanRequested || (currentState != null && currentState.autoScanning)) {
            showCarDialog(new AlertDialog.Builder(this)
                    .setTitle(R.string.action_auto_store)
                    .setItems(
                            new String[]{tr("Pysäytä automaattihaku", "Stop automatic scan")},
                            (ignored, which) -> stopAutoScanByUser()
                    )
                    .setNegativeButton(tr("Sulje", "Close"), null));
            return;
        }
        if (stationNameResolutionActive) {
            showCarDialog(new AlertDialog.Builder(this)
                    .setTitle(R.string.action_auto_store)
                    .setItems(
                            new String[]{tr(
                                    "Lopeta asemien nimeäminen",
                                    "Stop naming stations"
                            )},
                            (ignored, which) -> stopStationNameResolutionByUser()
                    )
                    .setNegativeButton(tr("Sulje", "Close"), null));
            return;
        }
        showCarDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.action_auto_store)
                .setItems(
                        new String[]{
                                getString(R.string.tuning_automatic),
                                getString(R.string.tuning_manual)
                        },
                        (ignored, which) -> {
                            if (which == 0) {
                                startAutoScan();
                            } else {
                                showManualTuningDialog();
                            }
                        }
                )
                .setNegativeButton(tr("Sulje", "Close"), null));
    }

    private void showManualTuningDialog() {
        if (manualTuningDialog != null && manualTuningDialog.isShowing()) {
            updateManualTuningDialog(currentState);
            return;
        }
        int band = currentState == null ? 0 : currentState.band;
        boolean fm = FrequencyRules.isFm(band);
        manualFmBand = fm ? band : 0;
        if (currentState != null) {
            if (fm) {
                manualFmFrequency = currentState.frequency;
            } else {
                manualAmFrequency = currentState.frequency;
            }
        }
        manualTuningBand = band;
        manualTunePerformed = false;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        int padding = dp(18);
        TextView saveHint = new TextView(this);
        saveHint.setText(tr(
                "Viritetyt asemat tallennetaan asemalistaan.",
                "Tuned stations are saved to the station list."
        ));
        saveHint.setTextSize(18f);
        saveHint.setPadding(padding, dp(4), padding, dp(4));
        content.addView(saveHint);
        RadioGroup bandChoices = new RadioGroup(this);
        bandChoices.setOrientation(LinearLayout.HORIZONTAL);
        bandChoices.setBaselineAligned(false);
        bandChoices.setPadding(padding, dp(2), padding, 0);
        manualFmChoice = createManualChoice("FM");
        manualAmChoice = createManualChoice("AM");
        bandChoices.addView(manualFmChoice, manualChoiceLayoutParams());
        bandChoices.addView(manualAmChoice, manualChoiceLayoutParams());
        manualChoiceUiUpdating = true;
        (fm ? manualFmChoice : manualAmChoice).setChecked(true);
        manualChoiceUiUpdating = false;
        content.addView(bandChoices);

        RadioGroup sensitivityChoices = new RadioGroup(this);
        sensitivityChoices.setOrientation(LinearLayout.HORIZONTAL);
        sensitivityChoices.setBaselineAligned(false);
        sensitivityChoices.setPadding(padding, 0, padding, 0);
        manualLocalChoice = createManualChoice(tr(
                "LOCAL vahvat asemat",
                "LOCAL strong stations"
        ));
        manualDxChoice = createManualChoice(tr(
                "DX myös heikot",
                "DX weak stations too"
        ));
        manualLocalChoice.setEnabled(RadioApiFactory.selectedSupportsLocalMode());
        manualDxChoice.setEnabled(RadioApiFactory.selectedSupportsLocalMode());
        sensitivityChoices.addView(manualLocalChoice, manualChoiceLayoutParams());
        sensitivityChoices.addView(manualDxChoice, manualChoiceLayoutParams());
        manualChoiceUiUpdating = true;
        boolean localMode = currentState != null && currentState.localMode;
        (localMode ? manualLocalChoice : manualDxChoice).setChecked(true);
        manualChoiceUiUpdating = false;
        content.addView(sensitivityChoices);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(padding, dp(2), padding, dp(2));

        Button lower = new Button(this);
        lower.setText("−");
        lower.setTextSize(26f);
        lower.setMinHeight(dp(52));
        row.addView(lower, new LinearLayout.LayoutParams(dp(58), -2));

        EditText input = new EditText(this);
        input.setGravity(Gravity.CENTER);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setSingleLine(true);
        input.setTextSize(24f);
        input.setMinHeight(dp(52));
        input.setSelectAllOnFocus(true);
        String currentValue = currentState == null
                ? (fm ? "101.7" : "999")
                : (fm
                ? FavoriteStation.formatFmFrequency(currentState.frequency)
                : String.valueOf(currentState.frequency));
        input.setText(currentValue);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        inputParams.setMargins(dp(8), 0, dp(8), 0);
        row.addView(input, inputParams);

        Button higher = new Button(this);
        higher.setText("+");
        higher.setTextSize(26f);
        higher.setMinHeight(dp(52));
        row.addView(higher, new LinearLayout.LayoutParams(dp(58), -2));

        content.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setMinHeight(dp(34));
        status.setPadding(padding, 0, padding, dp(2));
        status.setTextSize(16f);
        status.setTextColor(Color.WHITE);
        content.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setPadding(padding, 0, padding, dp(2));
        Button seekLower = new Button(this);
        seekLower.setText(tr("◀  Etsi alempi", "◀  Seek lower"));
        seekLower.setTextSize(16f);
        seekLower.setMinHeight(dp(48));
        Button seekHigher = new Button(this);
        seekHigher.setText(tr("Etsi ylempi  ▶", "Seek higher  ▶"));
        seekHigher.setTextSize(16f);
        seekHigher.setMinHeight(dp(48));
        LinearLayout.LayoutParams seekButtonParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        seekRow.addView(seekLower, seekButtonParams);
        LinearLayout.LayoutParams higherSeekParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        higherSeekParams.setMarginStart(dp(10));
        seekRow.addView(seekHigher, higherSeekParams);
        content.addView(seekRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        manualTuningBand = band;
        manualTuningInput = input;
        manualTuningStatus = status;
        manualTuningRequestedFrequency = currentState != null
                && currentState.band == band
                ? currentState.frequency
                : -1;

        bandChoices.setOnCheckedChangeListener((group, checkedId) -> {
            if (!manualChoiceUiUpdating) {
                switchManualTuningBand(checkedId == manualFmChoice.getId());
            }
        });
        sensitivityChoices.setOnCheckedChangeListener((group, checkedId) -> {
            if (!manualChoiceUiUpdating) {
                setReceptionMode(checkedId == manualLocalChoice.getId());
            }
        });

        lower.setOnClickListener(view -> requestManualLiveTune(
                input,
                manualTuningBand,
                shiftManualInput(input, manualTuningBand, -1)
        ));
        higher.setOnClickListener(view -> requestManualLiveTune(
                input,
                manualTuningBand,
                shiftManualInput(input, manualTuningBand, 1)
        ));
        seekLower.setOnClickListener(view -> {
            manualTuningRequestedFrequency = -1;
            beginManualSeekCapture();
            status.setText(tr("Etsitään alempaa asemaa…", "Seeking a lower station…"));
            seekTunerFrequency(false);
        });
        seekHigher.setOnClickListener(view -> {
            manualTuningRequestedFrequency = -1;
            beginManualSeekCapture();
            status.setText(tr("Etsitään ylempää asemaa…", "Seeking a higher station…"));
            seekTunerFrequency(true);
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            Integer frequency = parseManualFrequency(input, manualTuningBand);
            if (frequency != null) {
                requestManualCatalogTune(input, manualTuningBand, frequency);
            }
            return true;
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(tr("Manuaalinen viritys", "Manual tuning"))
                .setView(scrollableManualContent(content))
                .setNegativeButton(tr("Sulje", "Close"), null)
                .setPositiveButton(tr("Viritä", "Tune"), null)
                .create();
        manualTuningDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            persistFinalManualSelectionIfNeeded();
            manualTuningDialog = null;
            manualTuningInput = null;
            manualTuningStatus = null;
            manualTuningBand = -1;
            manualTuningRequestedFrequency = -1;
            manualFmChoice = null;
            manualAmChoice = null;
            manualLocalChoice = null;
            manualDxChoice = null;
            manualTunePerformed = false;
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            );
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                int selectedBand = manualTuningBand;
                Integer frequency = parseManualFrequency(input, selectedBand);
                if (frequency == null) {
                    return;
                }
                requestManualCatalogTune(input, selectedBand, frequency);
            });
            RadioPlaybackService.ensureRunning(this);
            updateManualTuningDialog(currentState);
        });
        dialog.show();
        styleCarDialog(dialog);
    }

    private RadioButton createManualChoice(String label) {
        RadioButton choice = new RadioButton(this);
        choice.setId(View.generateViewId());
        choice.setText(label);
        choice.setTextColor(Color.WHITE);
        choice.setTextSize(18f);
        choice.setGravity(Gravity.CENTER_VERTICAL);
        choice.setButtonTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.accent)
        ));
        choice.setMinHeight(dp(48));
        return choice;
    }

    private android.widget.ScrollView scrollableManualContent(View content) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(content);
        return scroll;
    }

    private RadioGroup.LayoutParams manualChoiceLayoutParams() {
        return new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void switchManualTuningBand(boolean selectFm) {
        if (manualTuningInput == null || manualTuningBand < 0) {
            return;
        }
        Integer currentInput = parseManualFrequency(
                manualTuningInput,
                manualTuningBand
        );
        if (currentInput != null) {
            if (FrequencyRules.isFm(manualTuningBand)) {
                manualFmBand = manualTuningBand;
                manualFmFrequency = currentInput;
            } else {
                manualAmFrequency = currentInput;
            }
        }
        int nextBand = selectFm ? manualFmBand : 3;
        int nextFrequency = selectFm ? manualFmFrequency : manualAmFrequency;
        manualTuningBand = nextBand;
        manualTuningRequestedFrequency = nextFrequency;
        requestManualLiveTune(manualTuningInput, nextBand, nextFrequency);
    }

    private void setReceptionMode(boolean localMode) {
        if (!RadioApiFactory.selectedSupportsLocalMode()) {
            showUnverifiedBackendFeature();
            return;
        }
        manualChoiceUiUpdating = true;
        if (manualLocalChoice != null && manualDxChoice != null) {
            (localMode ? manualLocalChoice : manualDxChoice).setChecked(true);
        }
        manualChoiceUiUpdating = false;
        if (currentState == null) {
            toast(tr(
                    "Odota radiotietojen latautumista",
                    "Wait for the radio information to load"
            ));
            return;
        }
        if (currentState.localMode == localMode) {
            return;
        }
        if (debugPreview) {
            RadioServiceClient.RadioState state = currentState;
            onStateChanged(new RadioServiceClient.RadioState(
                    state.band,
                    state.frequency,
                    state.rdsName,
                    state.radioText,
                    state.programType,
                    state.rdsAvailable,
                    state.stereo,
                    localMode,
                    state.autoScanning,
                    state.scanning,
                    state.seeking,
                    state.oemFavorite
            ));
            return;
        }
        runRadio(remote -> remote.onLocDxEvent());
    }

    private void requestManualCatalogTune(EditText input, int band, int frequency) {
        pendingManualCatalogBand = band;
        pendingManualCatalogFrequency = frequency;
        if (!requestManualLiveTune(input, band, frequency)) {
            pendingManualCatalogBand = -1;
            pendingManualCatalogFrequency = -1;
        }
    }

    private boolean requestManualLiveTune(EditText input, int band, int frequency) {
        manualTunePerformed = true;
        manualTuningRequestedFrequency = frequency;
        input.setText(FrequencyRules.isFm(band)
                ? FavoriteStation.formatFmFrequency(frequency)
                : String.valueOf(frequency));
        input.setSelection(input.length());
        input.setError(null);
        if (manualTuningStatus != null) {
            manualTuningStatus.setText(tr("Viritetään ", "Tuning ")
                    + formatFrequency(band, frequency) + "…");
        }
        return liveTuneManualFrequency(band, frequency);
    }

    private void updateManualTuningDialog(RadioServiceClient.RadioState state) {
        if (manualTuningDialog == null
                || !manualTuningDialog.isShowing()
                || manualTuningInput == null
                || manualTuningStatus == null
                || state == null) {
            return;
        }
        manualChoiceUiUpdating = true;
        if (manualLocalChoice != null && manualDxChoice != null) {
            (state.localMode ? manualLocalChoice : manualDxChoice).setChecked(true);
        }
        if (manualFmChoice != null && manualAmChoice != null) {
            (FrequencyRules.isFm(manualTuningBand)
                    ? manualFmChoice
                    : manualAmChoice).setChecked(true);
        }
        manualChoiceUiUpdating = false;
        if (state.band != manualTuningBand) {
            return;
        }
        boolean busy = state.seeking || state.scanning || state.autoScanning;
        if (manualTuningRequestedFrequency < 0 && !busy) {
            manualTuningRequestedFrequency = state.frequency;
        }
        boolean confirmed = ManualTuneConfirmation.isConfirmed(
                manualTuningBand,
                manualTuningRequestedFrequency,
                state.band,
                state.frequency,
                busy
        );
        if (confirmed || manualTuningRequestedFrequency < 0) {
            if (FrequencyRules.isFm(state.band)) {
                manualFmBand = state.band;
                manualFmFrequency = state.frequency;
            } else {
                manualAmFrequency = state.frequency;
            }
            manualTuningInput.setText(FrequencyRules.isFm(state.band)
                    ? FavoriteStation.formatFmFrequency(state.frequency)
                    : String.valueOf(state.frequency));
            manualTuningInput.setSelection(manualTuningInput.length());
        }
        if (busy) {
            manualTuningStatus.setText(tr("Etsitään asemaa…", "Seeking a station…"));
        } else if (!confirmed) {
            manualTuningStatus.setText(tr("Viritetään ", "Tuning ")
                    + formatFrequency(manualTuningBand, manualTuningRequestedFrequency)
                    + "…");
        } else {
            FavoriteStation known = findKnownStation(state.band, state.frequency);
            String stationName = known == null ? "" : stationName(known.name);
            if (stationName.isEmpty()) {
                stationName = state.rdsName;
            }
            StringBuilder message = new StringBuilder()
                    .append(tr("Viritetty • ", "Tuned • "))
                    .append(formatFrequency(state.band, state.frequency));
            if (!stationName.isEmpty()) {
                message.append(" • ").append(stationName);
            } else {
                message.append(" • ").append(tr(
                        "Ei RDS-nimeä — kuuntele vastaanottoa",
                        "No RDS name — listen to the reception"
                ));
            }
            if (state.stereo) {
                message.append(" • STEREO");
            }
            manualTuningStatus.setText(message.toString());
        }
        Button save = manualTuningDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (save != null) {
            save.setEnabled(confirmed);
        }
    }

    private void beginManualSeekCapture() {
        manualTunePerformed = true;
        pendingManualCatalogBand = -1;
        pendingManualCatalogFrequency = -1;
        pendingManualSeekOriginKey = currentState == null
                ? ""
                : currentState.band + ":" + currentState.frequency;
        pendingManualSeekAt = SystemClock.elapsedRealtime();
    }

    private void persistFinalManualSelectionIfNeeded() {
        RadioServiceClient.RadioState state = currentState;
        if (!manualTunePerformed
                || state == null
                || stationStore.find(state.band, state.frequency) != null
                || !ManualTuneConfirmation.isConfirmed(
                manualTuningBand,
                manualTuningRequestedFrequency,
                state.band,
                state.frequency,
                state.seeking || state.scanning || state.autoScanning
        )) {
            return;
        }
        persistManualStation(state);
    }

    private void captureManualStationIfReady(RadioServiceClient.RadioState state) {
        boolean busy = state.seeking || state.scanning || state.autoScanning;
        if (ManualTuneConfirmation.isConfirmed(
                pendingManualCatalogBand,
                pendingManualCatalogFrequency,
                state.band,
                state.frequency,
                busy
        )) {
            pendingManualCatalogBand = -1;
            pendingManualCatalogFrequency = -1;
            persistManualStation(state);
        }

        if (pendingManualSeekOriginKey.isEmpty()) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime() - pendingManualSeekAt;
        if (elapsed > MANUAL_SEEK_CAPTURE_TIMEOUT_MS) {
            pendingManualSeekOriginKey = "";
            return;
        }
        String stateKey = state.band + ":" + state.frequency;
        if (!busy
                && !stateKey.equals(pendingManualSeekOriginKey)
                && FrequencyRules.isValid(state.band, state.frequency)) {
            pendingManualSeekOriginKey = "";
            persistManualStation(state);
        }
    }

    private void persistManualStation(RadioServiceClient.RadioState state) {
        FavoriteStation existing = findKnownStation(state.band, state.frequency);
        String name = existing == null
                ? RadioMetadataReader.clean(state.rdsName)
                : existing.name;
        String logo = existing == null ? "" : existing.logo;
        boolean added = stationStore.find(state.band, state.frequency) == null;
        stationStore.save(new FavoriteStation(
                state.band,
                state.frequency,
                name,
                logo
        ));
        refreshFavorites();
        toast(added
                ? tr("Lisätty asemalistaan", "Added to station list")
                : tr("Asemalista päivitetty", "Station list updated"));
    }

    private int shiftManualInput(EditText input, int band, int direction) {
        boolean fm = FrequencyRules.isFm(band);
        int currentFrequency;
        try {
            if (fm) {
                double value = Double.parseDouble(input.getText().toString().replace(',', '.'));
                currentFrequency = (int) Math.round(value * 1000.0);
            } else {
                currentFrequency = Integer.parseInt(input.getText().toString());
            }
        } catch (NumberFormatException ignored) {
            currentFrequency = currentState != null && currentState.band == band
                    ? currentState.frequency
                    : (fm ? 101_700 : 999);
        }
        int next = FrequencyRules.stepFrom(band, currentFrequency, direction);
        input.setText(fm
                ? FavoriteStation.formatFmFrequency(next)
                : String.valueOf(next));
        input.setSelection(input.length());
        input.setError(null);
        return next;
    }

    private Integer parseManualFrequency(EditText input, int band) {
        String raw = input.getText().toString().trim().replace(',', '.');
        try {
            int frequency = FrequencyRules.isFm(band)
                    ? (int) Math.round(Double.parseDouble(raw) * 1000.0)
                    : Integer.parseInt(raw);
            if (!FrequencyRules.isValid(band, frequency)) {
                throw new NumberFormatException();
            }
            input.setError(null);
            return frequency;
        } catch (NumberFormatException exception) {
            input.setError(FrequencyRules.isFm(band)
                    ? tr(
                            "Anna 100 kHz askel väliltä 87.5–108.0",
                            "Enter a 100 kHz step between 87.5 and 108.0"
                    )
                    : tr(
                            "Anna 9 kHz askel väliltä 522–1620",
                            "Enter a 9 kHz step between 522 and 1620"
                    ));
            return null;
        }
    }

    private boolean liveTuneManualFrequency(int band, int frequency) {
        if (!FrequencyRules.isValid(band, frequency)) {
            return false;
        }
        manualTuneKey = band + ":" + frequency;
        if (debugPreview) {
            FavoriteStation known = findKnownStation(band, frequency);
            FavoriteStation favorite = favoriteStore.find(band, frequency);
            onStateChanged(new RadioServiceClient.RadioState(
                    band,
                    frequency,
                    known == null ? "" : known.name,
                    "",
                    "",
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    favorite != null
            ));
            return true;
        }
        if (!radioClient.isConnected()) {
            toast(tr(
                    "Radio ei vastaa — viritystä ei tehty",
                    "The radio is not responding — tuning was not performed"
            ));
            return false;
        }
        radioClient.tuneTo(band, frequency);
        return true;
    }


    private boolean runRadio(RadioServiceClient.RemoteAction action) {
        if (!radioClient.isConnected()) {
            toast(tr(
                    "Radio ei vastaa — yritä uudelleen",
                    "The radio is not responding — try again"
            ));
            return false;
        }
        radioClient.perform(action);
        return true;
    }

    private void startAutoScan() {
        if (!RadioApiFactory.selectedSupportsScanning()) {
            showUnverifiedBackendFeature();
            return;
        }
        if (autoScanRequested
                || autoScanFinalizing
                || stationNameResolutionActive
                || (currentState != null && currentState.autoScanning)) {
            return;
        }
        if (currentState == null) {
            toast(tr(
                    "Odota radiotietojen latautumista",
                    "Wait for the radio information to load"
            ));
            return;
        }
        if (!debugPreview && !radioClient.isConnected()) {
            toast(tr(
                    "Radio ei vastaa — automaattihakua ei käynnistetty",
                    "The radio is not responding — automatic scan was not started"
            ));
            return;
        }
        manualTuneKey = "";
        beginAutoScanTracking();
        if (debugPreview) {
            mainHandler.postDelayed(
                    () -> finishAutoScan(currentState == null ? 0 : currentState.band),
                    15000L
            );
            return;
        }
        if (!runRadio(remote -> remote.onASEvent())) {
            cancelAutoScan(tr(
                    "Automaattihakua ei voitu käynnistää",
                    "Automatic scan could not be started"
            ));
        }
    }

    private void beginAutoScanTracking() {
        autoScanRequested = true;
        autoScanObserved = false;
        autoScanStopRequested = false;
        autoScanFinalizing = false;
        autoScanRequestedAt = SystemClock.elapsedRealtime();
        observedScanFrequencies.clear();
        observedScanNames.clear();
        updateScanUi(true, tr(
                "Etsitään koko taajuusalueelta…",
                "Scanning the full frequency range…"
        ));
        mainHandler.removeCallbacks(autoScanStartTimeoutTask);
        mainHandler.removeCallbacks(autoScanHardTimeoutTask);
        mainHandler.removeCallbacks(autoScanStopTimeoutTask);
        mainHandler.postDelayed(
                autoScanStartTimeoutTask,
                AUTO_SCAN_START_GRACE_MS + 1200L
        );
        mainHandler.postDelayed(autoScanHardTimeoutTask, AUTO_SCAN_MAX_MS);
    }

    private void handleAutoScanStartTimeout() {
        if (!autoScanRequested || autoScanObserved) {
            return;
        }
        if (currentState == null) {
            cancelAutoScan(tr(
                    "Automaattihaku keskeytyi ennen käynnistymistä",
                    "Automatic scan stopped before it started"
            ));
        } else if (!currentState.autoScanning) {
            finishAutoScan(currentState.band);
        }
    }

    private void handleAutoScanHardTimeout() {
        if (!autoScanRequested) {
            return;
        }
        int band = currentState == null ? 0 : currentState.band;
        finishAutoScan(band);
    }

    private void handleAutoScanStopTimeout() {
        if (!autoScanRequested || !autoScanStopRequested) {
            return;
        }
        int band = currentState == null ? 0 : currentState.band;
        finishAutoScan(band);
    }

    private void stopAutoScanByUser() {
        if (!autoScanRequested
                && (currentState == null || !currentState.autoScanning)) {
            return;
        }
        if (autoScanStopRequested) {
            return;
        }
        autoScanStopRequested = true;
        updateScanUi(true, tr(
                "Pysäytetään automaattihakua…",
                "Stopping automatic scan…"
        ));
        if (debugPreview) {
            finishAutoScan(currentState == null ? 0 : currentState.band);
            return;
        }
        if (radioClient.isConnected()) {
            radioClient.perform(remote -> remote.onASEvent());
        }
        mainHandler.removeCallbacks(autoScanStopTimeoutTask);
        mainHandler.postDelayed(
                autoScanStopTimeoutTask,
                AUTO_SCAN_STOP_GRACE_MS
        );
    }

    private void cancelAutoScan(String message) {
        autoScanRequested = false;
        autoScanObserved = false;
        autoScanStopRequested = false;
        autoScanFinalizing = false;
        mainHandler.removeCallbacks(autoScanStartTimeoutTask);
        mainHandler.removeCallbacks(autoScanHardTimeoutTask);
        mainHandler.removeCallbacks(autoScanStopTimeoutTask);
        updateScanUi(false, message);
        toast(message);
    }

    private void finishAutoScan(int band) {
        if (!autoScanRequested) {
            return;
        }
        boolean stoppedByUser = autoScanStopRequested;
        autoScanRequested = false;
        autoScanObserved = false;
        autoScanStopRequested = false;
        autoScanFinalizing = true;
        mainHandler.removeCallbacks(autoScanStartTimeoutTask);
        mainHandler.removeCallbacks(autoScanHardTimeoutTask);
        mainHandler.removeCallbacks(autoScanStopTimeoutTask);
        updateScanUi(true, tr(
                "Viimeistellään asemalistaa…",
                "Finalizing station list…"
        ));
        radioClient.readPresetFrequencies(band, (resultBand, frequencies, vendorAvailable) -> {
            if (destroyed) {
                return;
            }
            autoScanFinalizing = false;
            int[] selected = frequencies;
            if (selected.length == 0
                    && !observedScanFrequencies.isEmpty()
                    && observedScanFrequencies.size() <= 30) {
                selected = new int[observedScanFrequencies.size()];
                int index = 0;
                for (Integer frequency : observedScanFrequencies) {
                    selected[index++] = frequency;
                }
            }
            int added = stationStore.mergeDiscovered(
                    resultBand,
                    selected,
                    observedScanNames
            );
            favoritePage = 0;
            favoritePageManuallySelected = false;
            showStationCatalog(false);
            if (stoppedByUser) {
                completeStoppedScan(selected.length, added);
                return;
            }
            beginStationNameResolution(
                    resultBand,
                    selected,
                    added,
                    vendorAvailable
            );
        });
    }

    private void completeStoppedScan(int found, int added) {
        String message = String.format(
                Locale.getDefault(),
                tr(
                        "Haku pysäytetty • %d asemaa säilytettiin • %d uutta",
                        "Scan stopped • %d stations kept • %d new"
                ),
                found,
                added
        );
        updateScanUi(false, message);
        toast(message);
    }

    private void beginStationNameResolution(
            int band,
            int[] frequencies,
            int added,
            boolean vendorAvailable
    ) {
        completedScanFound = frequencies.length;
        completedScanAdded = added;
        completedScanVendorAvailable = vendorAvailable;

        ArrayList<Integer> unnamed = new ArrayList<>();
        for (int frequency : frequencies) {
            FavoriteStation station = findKnownStation(band, frequency);
            if (station != null && station.name.isEmpty()) {
                unnamed.add(frequency);
            }
        }
        if (unnamed.isEmpty() || debugPreview || !radioClient.isConnected()) {
            completeScan();
            return;
        }

        stationNameFrequencies = new int[unnamed.size()];
        for (int index = 0; index < unnamed.size(); index++) {
            stationNameFrequencies[index] = unnamed.get(index);
        }
        stationNameBand = band;
        stationNameIndex = 0;
        stationNameAttempt = 0;
        stationNameReturnBand = currentState == null ? band : currentState.band;
        stationNameReturnFrequency = currentState == null ? -1 : currentState.frequency;
        stationNameResolutionActive = true;
        resolveNextStationName();
    }

    private void resolveNextStationName() {
        if (!stationNameResolutionActive || destroyed || !activityStarted) {
            return;
        }
        if (!radioClient.isConnected()) {
            return;
        }
        while (stationNameIndex < stationNameFrequencies.length) {
            FavoriteStation station = findKnownStation(
                    stationNameBand,
                    stationNameFrequencies[stationNameIndex]
            );
            if (station != null && station.name.isEmpty()) {
                break;
            }
            stationNameIndex++;
        }
        if (stationNameIndex >= stationNameFrequencies.length) {
            finishStationNameResolution();
            return;
        }

        int targetFrequency = stationNameFrequencies[stationNameIndex];
        stationNameAttempt = 0;
        updateScanUi(
                true,
                String.format(
                        Locale.getDefault(),
                        tr(
                                "Nimetään asemia %d/%d…",
                                "Naming stations %d/%d…"
                        ),
                        stationNameIndex + 1,
                        stationNameFrequencies.length
                )
        );
        requestStationNameTune(targetFrequency);
    }

    private void requestStationNameTune(int targetFrequency) {
        stationNameAttempt++;
        stationNameTuneRequestedAt = SystemClock.elapsedRealtime();
        stationNameMatchedAt = 0L;
        radioClient.tuneTo(stationNameBand, targetFrequency);
        mainHandler.removeCallbacks(stationNameCaptureTask);
        mainHandler.postDelayed(
                stationNameCaptureTask,
                StationNameResolutionPolicy.POLL_INTERVAL_MS
        );
    }

    private void captureResolvedStationName() {
        if (!stationNameResolutionActive || destroyed || !activityStarted) {
            return;
        }
        if (!radioClient.isConnected()) {
            return;
        }
        if (stationNameIndex < 0
                || stationNameIndex >= stationNameFrequencies.length) {
            finishStationNameResolution();
            return;
        }
        int targetFrequency = stationNameFrequencies[stationNameIndex];
        boolean matchingState = currentState != null
                && currentState.band == stationNameBand
                && currentState.frequency == targetFrequency;
        long now = SystemClock.elapsedRealtime();
        if (matchingState && stationNameMatchedAt == 0L) {
            stationNameMatchedAt = now;
        }
        boolean hasRdsName = matchingState && !currentState.rdsName.isEmpty();
        StationNameResolutionPolicy.Decision decision =
                StationNameResolutionPolicy.decide(
                        matchingState,
                        hasRdsName,
                        Math.max(0L, now - stationNameTuneRequestedAt),
                        stationNameMatchedAt == 0L
                                ? 0L
                                : Math.max(0L, now - stationNameMatchedAt),
                        stationNameAttempt
                );
        if (decision == StationNameResolutionPolicy.Decision.SAVE) {
            updateKnownStationRdsName(
                    stationNameBand,
                    targetFrequency,
                    currentState.rdsName
            );
            refreshFavorites();
            advanceStationNameResolution();
            return;
        }
        if (decision == StationNameResolutionPolicy.Decision.WAIT) {
            mainHandler.postDelayed(
                    stationNameCaptureTask,
                    StationNameResolutionPolicy.POLL_INTERVAL_MS
            );
            return;
        }
        if (decision == StationNameResolutionPolicy.Decision.RETUNE) {
            requestStationNameTune(targetFrequency);
            return;
        }
        advanceStationNameResolution();
    }

    private void advanceStationNameResolution() {
        stationNameIndex++;
        stationNameAttempt = 0;
        stationNameTuneRequestedAt = 0L;
        stationNameMatchedAt = 0L;
        resolveNextStationName();
    }

    private void finishStationNameResolution() {
        mainHandler.removeCallbacks(stationNameCaptureTask);
        stationNameResolutionActive = false;
        stationNameFrequencies = new int[0];
        stationNameAttempt = 0;
        stationNameTuneRequestedAt = 0L;
        stationNameMatchedAt = 0L;
        if (radioClient.isConnected()
                && FrequencyRules.isValid(
                stationNameReturnBand,
                stationNameReturnFrequency
        )) {
            radioClient.tuneTo(stationNameReturnBand, stationNameReturnFrequency);
        }
        stationNameReturnBand = -1;
        stationNameReturnFrequency = -1;
        refreshFavorites();
        completeScan();
    }

    private void stopStationNameResolutionByUser() {
        if (!stationNameResolutionActive) {
            return;
        }
        mainHandler.removeCallbacks(stationNameCaptureTask);
        stationNameResolutionActive = false;
        stationNameFrequencies = new int[0];
        stationNameAttempt = 0;
        stationNameTuneRequestedAt = 0L;
        stationNameMatchedAt = 0L;
        if (radioClient.isConnected()
                && FrequencyRules.isValid(
                stationNameReturnBand,
                stationNameReturnFrequency
        )) {
            radioClient.tuneTo(stationNameReturnBand, stationNameReturnFrequency);
        }
        stationNameReturnBand = -1;
        stationNameReturnFrequency = -1;
        refreshFavorites();
        updateScanUi(false, tr(
                "Asemien nimeäminen lopetettiin",
                "Station naming stopped"
        ));
        toast(tr(
                "Asemien nimeäminen lopetettiin • löydetyt asemat säilytettiin",
                "Station naming stopped • found stations were kept"
        ));
    }

    private void completeScan() {
        String message;
        if (completedScanFound > 0) {
            message = String.format(
                    Locale.getDefault(),
                    tr(
                            "Haku valmis • %d asemaa • %d uutta",
                            "Scan complete • %d stations • %d new"
                    ),
                    completedScanFound,
                    completedScanAdded
            );
        } else if (!completedScanVendorAvailable) {
            message = tr(
                    "Haku valmis • asemalistaa ei voitu lukea",
                    "Scan complete • station list could not be read"
            );
        } else {
            message = tr(
                    "Haku valmis • asemia ei löytynyt",
                    "Scan complete • no stations found"
            );
        }
        updateScanUi(false, message);
        toast(message);
    }

    private void tuneManualFrequency() {
        if (currentState == null) {
            toast(tr(
                    "Odota radiotietojen latautumista",
                    "Wait for the radio information to load"
            ));
            return;
        }
        String raw = manualFrequency.getText().toString().trim().replace(',', '.');
        if (raw.isEmpty()) {
            return;
        }
        try {
            int frequency;
            if (currentState.band < 3) {
                double mhz = Double.parseDouble(raw);
                frequency = (int) Math.round(mhz * 1000.0);
            } else {
                frequency = Integer.parseInt(raw);
            }
            if (!FrequencyRules.isValid(currentState.band, frequency)) {
                throw new NumberFormatException();
            }
            if (!radioClient.isConnected()) {
                toast(tr(
                        "Radio ei vastaa — viritystä ei tehty",
                        "The radio is not responding — tuning was not performed"
                ));
                return;
            }
            manualTuneKey = currentState.band + ":" + frequency;
            pendingManualCatalogBand = currentState.band;
            pendingManualCatalogFrequency = frequency;
            radioClient.tuneTo(currentState.band, frequency);
            manualFrequency.setText("");
            hideKeyboard();
        } catch (NumberFormatException error) {
            toast(currentState.band < 3
                    ? tr(
                            "Anna FM-taajuus väliltä 87.5–108.0, esimerkiksi 101.7",
                            "Enter an FM frequency between 87.5 and 108.0, for example 101.7"
                    )
                    : tr(
                            "Anna AM-taajuus väliltä 522–1620 kHz",
                            "Enter an AM frequency between 522 and 1620 kHz"
                    ));
        }
    }

    private void hideKeyboard() {
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null) {
            input.hideSoftInputFromWindow(manualFrequency.getWindowToken(), 0);
        }
        manualFrequency.clearFocus();
    }


    private void showStationListDialog() {
        List<FavoriteStation> stations = stationStore.load();
        if (stations.isEmpty()) {
            showCarDialog(new AlertDialog.Builder(this)
                    .setTitle(tr("Asemalista", "Station list"))
                    .setMessage(tr(
                            "Ei tallennettuja asemia. Käynnistä automaattihaku Viritys-valikosta.",
                            "No saved stations. Run an automatic scan first."
                    ))
                    .setPositiveButton("OK", null));
            return;
        }

        CharSequence[] labels = new CharSequence[stations.size()];
        for (int index = 0; index < stations.size(); index++) {
            FavoriteStation station = stations.get(index);
            labels[index] = station.name.isEmpty()
                    ? station.frequencyLabel()
                    : stationName(station.name) + "  •  " + station.frequencyLabel();
        }
        showCarDialog(new AlertDialog.Builder(this)
                .setTitle(tr("Asemalista", "Station list"))
                .setItems(labels, (ignored, which) -> onTune(stations.get(which)))
                .setNegativeButton(tr("Sulje", "Close"), null));
    }

    @Override
    public void onTune(FavoriteStation station) {
        if (!FrequencyRules.isValid(station.band, station.frequency)) {
            toast(tr(
                    "Tallennetun aseman taajuus ei ole kelvollinen",
                    "The saved station frequency is invalid"
            ));
            return;
        }
        manualTuneKey = "";
        favoritePageManuallySelected = false;
        boolean playbackActive = debugPreview
                ? !playbackPausedByStationTap
                : RadioPlaybackService.isPlaybackRequested();
        if (StationTapPlaybackPolicy.shouldPause(
                station.key(),
                activeFavoriteKey,
                playbackActive
        )) {
            playbackPausedByStationTap = true;
            pendingTuneKey = "";
            pendingTuneAt = 0L;
            if (pendingUnnamedStationPrompt != null) {
                mainHandler.removeCallbacks(pendingUnnamedStationPrompt);
                pendingUnnamedStationPrompt = null;
            }
            showActiveFavorite("", false);
            if (!debugPreview) {
                RadioPlaybackService.pause(this);
            }
            return;
        }
        playbackPausedByStationTap = false;
        if (debugPreview) {
            onStateChanged(new RadioServiceClient.RadioState(
                    station.band,
                    station.frequency,
                    station.name,
                    "",
                    "",
                    !station.name.isEmpty(),
                    true,
                    false,
                    false,
                    false,
                    false,
                    true
            ));
            scheduleUnnamedStationPrompt(station);
            return;
        }
        if (!radioClient.isConnected()) {
            toast(tr(
                    "Radio ei vastaa — yritä uudelleen",
                    "The radio is not responding — try again"
            ));
            return;
        }
        showActiveFavorite(station.key(), true);
        pendingTuneKey = station.key();
        pendingTuneAt = SystemClock.elapsedRealtime();
        RadioPlaybackService.tuneStation(this, station);
        scheduleUnnamedStationPrompt(station);
    }

    private void scheduleUnnamedStationPrompt(FavoriteStation station) {
        if (pendingUnnamedStationPrompt != null) {
            mainHandler.removeCallbacks(pendingUnnamedStationPrompt);
            pendingUnnamedStationPrompt = null;
        }
        if (station == null || !station.name.isEmpty()) {
            return;
        }
        String stationKey = station.key();
        pendingUnnamedStationPrompt = () -> {
            pendingUnnamedStationPrompt = null;
            if (destroyed || isFinishing()) {
                return;
            }
            FavoriteStation latest = findKnownStation(
                    station.band,
                    station.frequency
            );
            boolean stillSelected = currentState == null
                    || (currentState.band == station.band
                    && currentState.frequency == station.frequency)
                    || pendingTuneKey.equals(stationKey);
            if (latest != null && latest.name.isEmpty() && stillSelected) {
                onRename(latest);
            }
        };
        mainHandler.postDelayed(
                pendingUnnamedStationPrompt,
                debugPreview ? 250L : UNNAMED_STATION_PROMPT_DELAY_MS
        );
    }

    @Override
    public void onRename(FavoriteStation station) {
        String initial = station.name.isEmpty() ? "" : stationName(station.name);
        String title = station.name.isEmpty()
                ? tr("Nimeä ", "Name ") + station.frequencyLabel()
                : tr("Nimeä asema uudelleen", "Rename station");
        showNameDialog(title, initial, name -> {
            saveStationMetadata(station.withName(name));
            refreshFavorites();
            if (currentState != null
                    && currentState.band == station.band
                    && currentState.frequency == station.frequency) {
                renderState(currentState);
            }
        });
    }

    @Override
    public void onOptions(FavoriteStation station) {
        showStationOptions(station, showingFavorites);
    }

    private void showStationOptions(FavoriteStation station, boolean favoritesContext) {
        String title = station.name.isEmpty()
                ? station.frequencyLabel()
                : stationName(station.name);
        boolean favorite = favoriteStore.find(station.band, station.frequency) != null;
        String[] actions = {
                favorite
                        ? tr("Poista suosikeista", "Remove from favorites")
                        : tr("Lisää suosikiksi", "Add to favorites"),
                tr("Nimeä uudelleen", "Rename"),
                tr("Vaihda logo", "Change logo"),
                favoritesContext
                        ? tr("Järjestä suosikkeja", "Reorder favorites")
                        : tr("Järjestä asemia", "Reorder stations")
        };
        StationOptionsDialog dialog = new StationOptionsDialog(this, title, station.frequencyLabel(),
                actions, favorite, which -> {
                    if (which == 0) {
                        onToggleFavorite(station);
                    } else if (which == 1) {
                        onRename(station);
                    } else if (which == 2) {
                        showLogoDialog(station);
                    } else if (which == 3) {
                        showStationCatalog(favoritesContext);
                        int index = StationOrder.indexOf(displayedStations(), station.key());
                        if (index >= 0) {
                            favoritePage = index / FAVORITES_PER_PAGE;
                            favoritePageManuallySelected = true;
                            renderFavoritePage();
                            favoritesList.post(() -> {
                                if (!destroyed && showingFavorites == favoritesContext) {
                                    beginFavoriteReorder(station.key());
                                }
                            });
                        }
                    }
                });
        dialog.show();
    }

    @Override
    public void onToggleFavorite(FavoriteStation station) {
        FavoriteStation favorite = favoriteStore.find(station.band, station.frequency);
        boolean currentlyPlaying = currentState != null
                && currentState.band == station.band
                && currentState.frequency == station.frequency;
        if (favorite == null) {
            stationStore.save(station);
            favoriteStore.save(station);
            if (currentlyPlaying && !currentState.oemFavorite) {
                runRadio(remote -> {
                    if (RadioApiFactory.supportsOemFavorites(remote)) remote.favoriteCurrentFreq();
                });
            }
            toast(tr("Lisätty suosikkeihin", "Added to favorites"));
        } else {
            favoriteStore.delete(favorite);
            if (currentlyPlaying && currentState.oemFavorite) {
                runRadio(remote -> {
                    if (RadioApiFactory.supportsOemFavorites(remote)) remote.favoriteCurrentFreq();
                });
            }
            toast(tr("Poistettu suosikeista", "Removed from favorites"));
        }
        refreshFavorites();
        if (currentState != null) {
            renderState(currentState);
        }
    }

    private void showLogoDialog(FavoriteStation station) {
        if (!BuildConfig.BUNDLED_STATION_LOGOS) {
            showCarDialog(new AlertDialog.Builder(this)
                    .setTitle(tr("Aseman logo", "Station logo"))
                    .setItems(new String[]{
                            tr("Lisää oma logo laitteelta…", "Add custom logo from device…"),
                            tr("Poista logo", "Remove logo")
                    }, (ignored, which) -> {
                        if (which == 0) {
                            openLogoPicker(station);
                        } else {
                            setFavoriteLogo(station, StationLogoResolver.NO_LOGO);
                        }
                    })
                    .setNegativeButton(tr("Peruuta", "Cancel"), null));
            return;
        }
        String[] actions = new String[StationLogoResolver.BUILTIN_NAMES.length + 3];
        actions[0] = tr(
                "Automaattinen logo aseman nimestä",
                "Automatic logo from station name"
        );
        actions[1] = tr("Lisää oma logo laitteelta…", "Add custom logo from device…");
        actions[2] = tr("Poista logo", "Remove logo");
        System.arraycopy(
                StationLogoResolver.BUILTIN_NAMES,
                0,
                actions,
                3,
                StationLogoResolver.BUILTIN_NAMES.length
        );
        showCarDialog(new AlertDialog.Builder(this)
                .setTitle(tr("Aseman logo", "Station logo"))
                .setItems(actions, (ignored, which) -> {
                    if (which == 0) {
                        setFavoriteLogo(station, "");
                    } else if (which == 1) {
                        openLogoPicker(station);
                    } else if (which == 2) {
                        setFavoriteLogo(station, StationLogoResolver.NO_LOGO);
                    } else {
                        setFavoriteLogo(
                                station,
                                StationLogoResolver.BUILTIN_TOKENS[which - 3]
                        );
                    }
                })
                .setNegativeButton(tr("Peruuta", "Cancel"), null));
    }

    private void setFavoriteLogo(FavoriteStation station, String logoToken) {
        FavoriteStation latest = findKnownStation(station.band, station.frequency);
        if (latest == null) {
            return;
        }
        if (!latest.logo.equals(logoToken)) {
            FavoriteLogoStore.delete(this, latest.logo);
        }
        saveStationMetadata(latest.withLogo(logoToken));
        refreshFavorites();
        toast(StationLogoResolver.NO_LOGO.equals(logoToken)
                ? tr("Logo poistettu", "Logo removed")
                : logoToken.isEmpty()
                ? tr("Automaattinen logo käytössä", "Automatic logo enabled")
                : tr("Logo vaihdettu", "Logo changed"));
    }

    private void openLogoPicker(FavoriteStation station) {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("image/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        pendingLogoBand = station.band;
        pendingLogoFrequency = station.frequency;
        try {
            startActivityForResult(picker, REQUEST_STATION_LOGO);
        } catch (ActivityNotFoundException exception) {
            pendingLogoBand = -1;
            pendingLogoFrequency = -1;
            toast(tr(
                    "Laitteesta ei löytynyt kuvanvalitsinta",
                    "No image picker was found on the device"
            ));
        }
    }

    @Override
    public void onDelete(FavoriteStation station) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(tr("Poistetaanko suosikki?", "Remove favorite?"))
                .setMessage(station.name.isEmpty()
                        ? station.frequencyLabel()
                        : stationName(station.name))
                .setNegativeButton(tr("Peruuta", "Cancel"), null)
                .setPositiveButton(tr("Poista", "Delete"), (ignoredDialog, which) -> {
                    favoriteStore.delete(station);
                    refreshFavorites();
                })
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setAllCaps(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setAllCaps(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.danger));
        });
        dialog.show();
        styleCarDialog(dialog);
    }

    private interface NameResult {
        void onName(String name);
    }

    private void showNameDialog(String title, String initialValue, NameResult result) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(initialValue);
        input.setHint(tr("Esim. Radio Nova", "For example, Radio Nova"));
        input.setSelection(input.length());
        input.setTextSize(23f);
        input.setMinHeight(dp(66));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(MAX_STATION_NAME_LENGTH)
        });
        int padding = getResources().getDimensionPixelSize(R.dimen.dialog_input_padding);
        input.setPadding(padding, padding, padding, padding);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setNegativeButton(tr("Peruuta", "Cancel"), null)
                .setPositiveButton(tr("Tallenna", "Save"), null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setAllCaps(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setAllCaps(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.accent));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) {
                    input.setError(tr("Anna asemalle nimi", "Enter a station name"));
                    return;
                }
                result.onName(name);
                dialog.dismiss();
            });
        });
        dialog.show();
        styleCarDialog(dialog);
    }

    @Override
    public void onConnectionChanged(boolean connected, String message) {
        if (destroyed) {
            return;
        }
        radioConnected = connected;
        // Connection state is intentionally not part of the visible car UI.
        connectionText.setText("");
        connectionDot.setVisibility(View.GONE);
        connectionText.setContentDescription(message);
        boolean busy = autoScanRequested
                || autoScanFinalizing
                || stationNameResolutionActive;
        setRadioControlsEnabled(connected && !busy);
        if (!connected && autoScanRequested) {
            cancelAutoScan(tr(
                    "Automaattihaku keskeytyi radioyhteyden katketessa",
                    "Automatic scan stopped when the radio connection was lost"
            ));
        } else if (connected && stationNameResolutionActive) {
            resolveNextStationName();
        }
        if (!connected && currentState == null) {
            frequencyText.setText("--.-");
            stationText.setText(tr("Valitse asema", "Select a station"));
            radioText.setText("");
            radioText.setVisibility(View.GONE);
            rdsStatusText.setText("RDS —");
            bandText.setText("FM");
            updateBandButton(0);
            unitText.setText("");
            stereoText.setText("—");
            receptionText.setText("—");
            activityText.setText(tr("VALMIS", "READY"));
            tunerBackdrop.setScanning(false);
            updateScanUi(false, tr(
                    "Valmis automaattihakuun",
                    "Ready for automatic scan"
            ));
        }
    }

    @Override
    public void onRadioError(String message) {
        if (!destroyed && activityStarted) {
            toast(message);
        }
    }

    @Override
    public void onStateChanged(RadioServiceClient.RadioState state) {
        if (destroyed) {
            return;
        }
        currentState = state;
        if (settingsDialog != null && settingsDialog.isShowing()) {
            settingsDialog.updateLocalMode(state.localMode);
        }
        captureManualStationIfReady(state);
        updateManualTuningDialog(state);
        RadioPlaybackService.publishMediaState(
                state.band,
                state.frequency,
                state.rdsName,
                state.radioText
        );
        String stateKey = state.band + ":" + state.frequency;
        if (!pendingTuneKey.isEmpty()) {
            if (pendingTuneKey.equals(stateKey)) {
                pendingTuneKey = "";
            } else if (SystemClock.elapsedRealtime() - pendingTuneAt
                    < TUNE_SELECTION_GRACE_MS) {
                stateKey = pendingTuneKey;
            } else {
                pendingTuneKey = "";
            }
        }
        if (!autoScanRequested
                && !autoScanFinalizing
                && !stationNameResolutionActive
                && state.autoScanning) {
            beginAutoScanTracking();
            autoScanObserved = true;
        }
        if (autoScanRequested && state.autoScanning) {
            autoScanObserved = true;
            if (FrequencyRules.isValid(state.band, state.frequency)) {
                observedScanFrequencies.add(state.frequency);
                if (!state.rdsName.isEmpty()) {
                    observedScanNames.put(state.frequency, state.rdsName);
                }
            }
        } else if (autoScanRequested
                && autoScanObserved
                && !state.autoScanning) {
            finishAutoScan(state.band);
        } else if (autoScanRequested
                && !state.autoScanning
                && SystemClock.elapsedRealtime() - autoScanRequestedAt > AUTO_SCAN_START_GRACE_MS) {
            finishAutoScan(state.band);
        }

        FavoriteStation cachedStation = findKnownStation(state.band, state.frequency);
        if (cachedStation != null
                && cachedStation.name.isEmpty()
                && updateKnownStationRdsName(
                state.band,
                state.frequency,
                state.rdsName
        )) {
            refreshFavorites();
        }
        renderState(state);
        boolean playbackActive = debugPreview
                ? !playbackPausedByStationTap
                : RadioPlaybackService.isPlaybackRequested();
        showActiveFavorite(
                playbackActive ? stateKey : "",
                !state.autoScanning && !state.scanning && !autoScanRequested
        );
    }

    private void renderState(RadioServiceClient.RadioState state) {
        boolean fm = state.band < 3;
        bandText.setText(fm ? "FM" + (state.band + 1) : "AM");
        updateBandButton(state.band);
        frequencyText.setText(fm
                ? FavoriteStation.formatFmFrequency(state.frequency)
                : String.valueOf(state.frequency));
        unitText.setText(fm ? "MHz" : "kHz");
        tunerBackdrop.setFrequency(state.band, state.frequency);
        tunerBackdrop.setScanning(
                state.scanning
                        || autoScanRequested
                        || autoScanFinalizing
                        || stationNameResolutionActive
        );

        FavoriteStation local = findKnownStation(state.band, state.frequency);
        String localName = local == null ? "" : stationName(local.name);
        String displayName = !localName.isEmpty()
                ? localName
                : (!state.rdsName.isEmpty()
                ? state.rdsName
                : (manualTuneKey.equals(state.band + ":" + state.frequency)
                ? tr("Manuaaliviritys", "Manual tuning")
                : formatFrequency(state.band, state.frequency)));
        stationText.setText(displayName.toUpperCase(Locale.getDefault()));

        if (!state.radioText.isEmpty()) {
            radioText.setText(state.radioText);
            radioText.setVisibility(View.VISIBLE);
        } else {
            radioText.setText("");
            radioText.setVisibility(View.GONE);
        }

        if (state.rdsAvailable) {
            rdsStatusText.setText("RDS LIVE");
            rdsStatusText.setTextColor(getColor(R.color.status_connected));
        } else {
            rdsStatusText.setText("RDS —");
            rdsStatusText.setTextColor(getColor(R.color.text_muted));
        }
        stereoText.setText(state.stereo ? "STEREO" : "MONO");
        receptionText.setText(state.localMode ? "LOCAL" : "DX");
        if (state.autoScanning || autoScanRequested || autoScanFinalizing) {
            activityText.setText(tr("Autohaku", "Auto scan"));
        } else if (state.scanning) {
            activityText.setText(tr("Skannaus", "Scanning"));
        } else if (state.seeking) {
            activityText.setText(tr("Haku", "Seeking"));
        } else if (!state.programType.isEmpty()) {
            activityText.setText(state.programType);
        } else {
            activityText.setText(tr("Toistetaan", "Playing"));
        }

        if (state.autoScanning || autoScanRequested || autoScanFinalizing) {
            updateScanUi(true, tr("Etsitään asemia…", "Scanning for stations…"));
        } else if (!stationNameResolutionActive) {
            scanProgress.setVisibility(View.GONE);
            autoScanButton.setEnabled(radioConnected || debugPreview);
            autoScanButton.setText(R.string.action_auto_store);
        }
    }

    private void updateBandButton(int band) {
        String label = BandUi.labelForBand(band);
        bandButton.setText(label);
        bandButton.setContentDescription(getString(
                R.string.content_switch_band,
                label
        ));
    }

    private void updateScanUi(boolean scanning, String summary) {
        boolean canControlRadio = radioConnected || debugPreview;
        setRadioControlsEnabled(!scanning && canControlRadio);
        scanProgress.setVisibility(scanning ? View.VISIBLE : View.GONE);
        autoScanButton.setText(R.string.action_auto_store);
        autoScanButton.setEnabled(
                canControlRadio
                        && !autoScanStopRequested
                        && !autoScanFinalizing
        );
        autoScanButton.setAlpha(
                autoScanButton.isEnabled() ? 1.0f : 0.42f
        );
        scanSummaryText.setText(summary);
        tunerBackdrop.setScanning(scanning);
    }

    private void setRadioControlsEnabled(boolean enabled) {
        for (View control : radioControls) {
            // Station lists and settings are local app screens. They must stay
            // usable while the vendor tuner service is starting or being
            // rebound; disabling them made the whole screen look frozen when
            // FMPlugService accepted a bind but never delivered its Binder.
            int id = control.getId();
            boolean locallyAvailable = id == R.id.stations_button
                    || id == R.id.saved_button
                    || id == R.id.more_button;
            boolean controlEnabled = enabled || locallyAvailable;
            if ((id == R.id.scan_button && !RadioApiFactory.selectedSupportsScanning())
                    || (id == R.id.local_button && !RadioApiFactory.selectedSupportsLocalMode())) {
                controlEnabled = false;
            }
            control.setEnabled(controlEnabled);
            control.setAlpha(controlEnabled ? 1.0f : 0.42f);
        }
    }

    private void showUnverifiedBackendFeature() {
        Toast.makeText(this, tr("Tätä toimintoa ei ole vielä varmistettu tälle vakioradiolle",
                "This feature has not yet been verified for this stock radio"), Toast.LENGTH_SHORT).show();
    }

    private void showActiveFavorite(String stationKey, boolean followStationPage) {
        String normalizedKey = stationKey == null ? "" : stationKey;
        int previousPage = favoritePage;
        if (!normalizedKey.isEmpty()) {
            List<FavoriteStation> displayed = displayedStations();
            for (int index = 0; index < displayed.size(); index++) {
                if (displayed.get(index).key().equals(normalizedKey)) {
                    favoritePage = StationPageSelectionPolicy.targetPage(
                            favoritePage,
                            index,
                            FAVORITES_PER_PAGE,
                            followStationPage,
                            favoritePageManuallySelected
                    );
                    break;
                }
            }
        }
        activeFavoriteKey = normalizedKey;
        if (!normalizedKey.equals(renderedFavoriteKey) || favoritePage != previousPage) {
            renderFavoritePage();
        }
    }

    private void refreshFavorites() {
        favoriteStations.clear();
        favoriteStations.addAll(favoriteStore.load());
        catalogStations.clear();
        catalogStations.addAll(stationStore.load());
        int pageCount = favoritePageCount();
        if (pageCount == 0) {
            favoritePage = 0;
        } else if (favoritePage >= pageCount) {
            favoritePage = pageCount - 1;
        }
        renderFavoritePage();
        List<FavoriteStation> displayed = displayedStations();
        emptyFavoritesText.setText(
                showingFavorites ? R.string.favorites_empty : R.string.stations_empty
        );
        emptyFavoritesText.setVisibility(
                displayed.isEmpty() ? View.VISIBLE : View.GONE
        );
        stationCountText.setText(String.valueOf(displayed.size()));
        updateCatalogNavigation();
    }

    private FavoriteStation findFavorite(int band, int frequency) {
        for (FavoriteStation station : favoriteStations) {
            if (station.band == band && station.frequency == frequency) {
                return station;
            }
        }
        return null;
    }

    private FavoriteStation findKnownStation(int band, int frequency) {
        FavoriteStation favorite = favoriteStore.find(band, frequency);
        return favorite == null ? stationStore.find(band, frequency) : favorite;
    }

    private void saveStationMetadata(FavoriteStation station) {
        stationStore.save(station);
        if (favoriteStore.find(station.band, station.frequency) != null) {
            favoriteStore.save(station);
        }
    }

    private boolean updateKnownStationRdsName(
            int band,
            int frequency,
            String rdsName
    ) {
        boolean catalogChanged = stationStore.updateRdsNameIfUnnamed(
                band,
                frequency,
                rdsName
        );
        boolean favoriteChanged = favoriteStore.updateRdsNameIfUnnamed(
                band,
                frequency,
                rdsName
        );
        return catalogChanged || favoriteChanged;
    }

    private List<FavoriteStation> displayedStations() {
        return showingFavorites ? favoriteStations : catalogStations;
    }

    private Set<String> favoriteKeys() {
        HashSet<String> keys = new HashSet<>();
        for (FavoriteStation station : favoriteStations) {
            keys.add(station.key());
        }
        return keys;
    }

    private void showStationCatalog(boolean favorites) {
        stationNavigationStore.setFavoritesSelected(favorites);
        if (showingFavorites != favorites) {
            showingFavorites = favorites;
            favoritePage = 0;
            favoritePageManuallySelected = false;
        }
        refreshFavorites();
    }

    private void updateCatalogNavigation() {
        stationsButton.setBackgroundResource(
                showingFavorites
                        ? R.drawable.bg_skoda_nav_item
                        : R.drawable.bg_skoda_nav_selected
        );
        savedButton.setBackgroundResource(
                showingFavorites
                        ? R.drawable.bg_skoda_nav_selected
                        : R.drawable.bg_skoda_nav_item
        );
        stationsButton.setSelected(!showingFavorites);
        savedButton.setSelected(showingFavorites);
        findViewById(R.id.seek_down_button).setContentDescription(getString(
                showingFavorites ? R.string.content_previous_favorite : R.string.content_previous_station
        ));
        findViewById(R.id.seek_up_button).setContentDescription(getString(
                showingFavorites ? R.string.content_next_favorite : R.string.content_next_station
        ));
    }

    private void renderFavoritePage() {
        List<FavoriteStation> displayed = displayedStations();
        int fromIndex = favoritePage * FAVORITES_PER_PAGE;
        int toIndex = Math.min(
                fromIndex + FAVORITES_PER_PAGE,
                displayed.size()
        );
        List<FavoriteStation> pageStations = fromIndex < toIndex
                ? new ArrayList<>(displayed.subList(fromIndex, toIndex))
                : new ArrayList<>();
        String currentKey = activeFavoriteKey;
        renderedFavoriteKey = currentKey;
        favoriteAdapter.submit(pageStations, currentKey, favoriteKeys());
        scheduleFavoriteGridLayout(pageStations.size());
        updateFavoritePageIndicator(favoritePageCount());
        if (!pageStations.isEmpty()) {
            favoritesList.setSelection(0);
        }
    }

    private int favoritePageCount() {
        return (displayedStations().size() + FAVORITES_PER_PAGE - 1)
                / FAVORITES_PER_PAGE;
    }

    private void showFavoritePage(int targetPage) {
        int pageCount = favoritePageCount();
        if (targetPage < 0 || targetPage >= pageCount || targetPage == favoritePage) {
            settleFavoritePage();
            return;
        }
        animateFavoritePageChange(
                targetPage,
                targetPage > favoritePage ? -1f : 1f
        );
    }

    private boolean handleFavoritePageTouch(View ignored, MotionEvent event) {
        if (favoritePageAnimating) {
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            cancelPendingFavoriteReorder();
            cancelPendingFavoriteOptions();
            favoritesList.animate().cancel();
            favoritesList.setTranslationX(0f);
            favoritesList.setAlpha(1f);
            favoritesList.setLayerType(View.LAYER_TYPE_NONE, null);
            favoriteTouchDownX = event.getX();
            favoriteTouchDownY = event.getY();
            favoriteTouchDownAt = event.getEventTime();
            favoriteTouchDragging = false;
            favoriteOptionsShown = false;
            favoriteTouchDownPosition = favoritesList.pointToPosition(
                    Math.round(event.getX()),
                    Math.round(event.getY())
            );
            scheduleFavoriteOptions(favoriteTouchDownPosition);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float deltaX = event.getX() - favoriteTouchDownX;
            float deltaY = event.getY() - favoriteTouchDownY;
            if (favoriteReordering) {
                updateFavoriteReorder(event);
                return true;
            }
            if (Math.hypot(deltaX, deltaY) >= dp(8)) {
                cancelPendingFavoriteReorder();
                cancelPendingFavoriteOptions();
            }
            if (!favoriteTouchDragging
                    && Math.abs(deltaX) >= dp(8)
                    && Math.abs(deltaX) > Math.abs(deltaY) * 1.05f) {
                favoriteTouchDragging = true;
                favoritesList.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }
            if (favoriteTouchDragging) {
                boolean canMove = deltaX < 0
                        ? favoritePage + 1 < favoritePageCount()
                        : favoritePage > 0;
                float translated = canMove ? deltaX : deltaX * 0.22f;
                favoritesList.setTranslationX(translated);
            }
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            cancelPendingFavoriteReorder();
            cancelPendingFavoriteOptions();
            if (favoriteReordering) {
                finishFavoriteReorder(true);
                return true;
            }
            favoriteTouchDragging = false;
            settleFavoritePage();
            return true;
        }
        if (event.getActionMasked() != MotionEvent.ACTION_UP) {
            return true;
        }

        cancelPendingFavoriteReorder();
        cancelPendingFavoriteOptions();
        if (favoriteReordering) {
            finishFavoriteReorder(true);
            return true;
        }
        float deltaX = event.getX() - favoriteTouchDownX;
        float deltaY = event.getY() - favoriteTouchDownY;
        if (favoriteOptionsShown) {
            favoriteOptionsShown = false;
            settleFavoritePage();
            return true;
        }
        long gestureDuration = Math.max(1L, event.getEventTime() - favoriteTouchDownAt);
        float velocityX = Math.abs(deltaX) * 1000f / gestureDuration;
        float minimumFlingVelocity = Math.max(
                ViewConfiguration.get(this).getScaledMinimumFlingVelocity(),
                dp(420)
        );
        boolean fling = Math.abs(deltaX) >= dp(24)
                && velocityX >= minimumFlingVelocity;
        boolean horizontalSwipe = (Math.abs(deltaX) >= dp(48) || fling)
                && Math.abs(deltaX) > Math.abs(deltaY) * 1.12f;
        if (!horizontalSwipe) {
            boolean stationaryTouch = Math.abs(deltaX) <= dp(24)
                    && Math.abs(deltaY) <= dp(24);
            int position = favoritesList.pointToPosition(
                    Math.round(event.getX()),
                    Math.round(event.getY())
            );
            if (stationaryTouch && position != GridView.INVALID_POSITION) {
                FavoriteStation station = favoriteAdapter.getItem(position);
                long pressDuration = event.getEventTime() - favoriteTouchDownAt;
                if (pressDuration >= ViewConfiguration.getLongPressTimeout()) {
                    onOptions(station);
                } else {
                    onTune(station);
                }
            }
            settleFavoritePage();
            return true;
        }
        favoriteTouchDragging = false;
        favoritePageManuallySelected = true;
        showFavoritePage(favoritePage + (deltaX < 0 ? 1 : -1));
        return true;
    }

    private void scheduleFavoriteOptions(int pagePosition) {
        if (pagePosition == GridView.INVALID_POSITION
                || pagePosition < 0
                || pagePosition >= favoriteAdapter.getCount()) {
            return;
        }
        FavoriteStation station = favoriteAdapter.getItem(pagePosition);
        pendingFavoriteOptions = () -> {
            pendingFavoriteOptions = null;
            if (destroyed || favoriteTouchDragging || favoritePageAnimating
                    || favoriteReordering) {
                return;
            }
            favoriteOptionsShown = true;
            favoritesList.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            onOptions(station);
        };
        mainHandler.postDelayed(
                pendingFavoriteOptions,
                ViewConfiguration.getLongPressTimeout()
        );
    }

    private void cancelPendingFavoriteOptions() {
        if (pendingFavoriteOptions != null) {
            mainHandler.removeCallbacks(pendingFavoriteOptions);
            pendingFavoriteOptions = null;
        }
    }

    private void animateFavoritePageChange(int targetPage, float direction) {
        int pageCount = favoritePageCount();
        if (favoritePageAnimating
                || targetPage < 0
                || targetPage >= pageCount
                || targetPage == favoritePage) {
            settleFavoritePage();
            return;
        }
        favoritePageAnimating = true;
        float width = Math.max(dp(480), favoritesList.getWidth());
        float exitX = direction * width;
        float remaining = Math.abs(exitX - favoritesList.getTranslationX());
        long exitDuration = clamp(
                Math.round((remaining / width) * 145f),
                (int) PAGE_EXIT_ANIMATION_MS,
                145
        );
        favoritesList.animate()
                .translationX(exitX)
                .setDuration(exitDuration)
                .setInterpolator(new AccelerateInterpolator())
                .withLayer()
                .withEndAction(() -> {
                    favoritesList.setLayerType(View.LAYER_TYPE_NONE, null);
                    favoritePage = targetPage;
                    renderFavoritePage();
                    favoritesList.setTranslationX(-direction * width);
                    favoritesList.setAlpha(1f);
                    favoritesList.animate()
                            .translationX(0f)
                            .setDuration(PAGE_ENTER_ANIMATION_MS)
                            .setInterpolator(new DecelerateInterpolator(1.8f))
                            .withLayer()
                            .withEndAction(() -> {
                                favoritePageAnimating = false;
                                favoritesList.setLayerType(
                                        View.LAYER_TYPE_NONE,
                                        null
                                );
                                favoritesList.announceForAccessibility(getString(
                                        R.string.station_page_description,
                                        favoritePage + 1,
                                        pageCount
                                ));
                            })
                            .start();
                })
                .start();
    }

    private void settleFavoritePage() {
        if (Math.abs(favoritesList.getTranslationX()) < 0.5f) {
            favoritesList.setTranslationX(0f);
            favoritesList.setLayerType(View.LAYER_TYPE_NONE, null);
            return;
        }
        favoritesList.animate()
                .translationX(0f)
                .setDuration(130L)
                .setInterpolator(new DecelerateInterpolator(1.7f))
                .withLayer()
                .withEndAction(() -> favoritesList.setLayerType(
                        View.LAYER_TYPE_NONE,
                        null
                ))
                .start();
    }

    private void scheduleFavoriteReorder(int pagePosition) {
        if (pagePosition == GridView.INVALID_POSITION
                || pagePosition < 0
                || pagePosition >= favoriteAdapter.getCount()) {
            return;
        }
        String stationKey = favoriteAdapter.getItem(pagePosition).key();
        pendingFavoriteReorder = () -> {
            pendingFavoriteReorder = null;
            if (destroyed || favoriteTouchDragging || favoritePageAnimating) {
                return;
            }
            beginFavoriteReorder(stationKey);
        };
        mainHandler.postDelayed(pendingFavoriteReorder, STATION_REORDER_HOLD_MS);
    }

    private void cancelPendingFavoriteReorder() {
        if (pendingFavoriteReorder != null) {
            mainHandler.removeCallbacks(pendingFavoriteReorder);
            pendingFavoriteReorder = null;
        }
    }

    private void beginFavoriteReorder(String stationKey) {
        int stationIndex = StationOrder.indexOf(displayedStations(), stationKey);
        if (stationIndex < 0) {
            return;
        }
        favoriteReordering = true;
        favoriteReorderIndex = stationIndex;
        favoriteReorderPageChangedAt = 0L;
        favoriteTouchDragging = false;
        favoritesList.animate().cancel();
        favoritesList.setTranslationX(0f);
        favoritesList.setLayerType(View.LAYER_TYPE_NONE, null);
        favoritesList.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        highlightFavoriteReorderCard();
        toast(tr(
                "Järjestä kanavia vetämällä",
                "Drag to reorder stations"
        ));
    }

    private void updateFavoriteReorder(MotionEvent event) {
        List<FavoriteStation> stations = displayedStations();
        if (!favoriteReordering || stations.isEmpty()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        int edge = dp(82);
        if (now - favoriteReorderPageChangedAt >= STATION_REORDER_PAGE_DELAY_MS) {
            if (event.getX() <= edge && favoritePage > 0) {
                moveFavoriteReorderTo(favoritePage * FAVORITES_PER_PAGE - 1);
                favoriteReorderPageChangedAt = now;
                return;
            }
            if (event.getX() >= favoritesList.getWidth() - edge
                    && favoritePage + 1 < favoritePageCount()) {
                moveFavoriteReorderTo(Math.min(
                        stations.size() - 1,
                        (favoritePage + 1) * FAVORITES_PER_PAGE
                ));
                favoriteReorderPageChangedAt = now;
                return;
            }
        }

        int pagePosition = favoritesList.pointToPosition(
                Math.round(event.getX()),
                Math.round(event.getY())
        );
        if (pagePosition == GridView.INVALID_POSITION) {
            return;
        }
        int targetIndex = Math.min(
                stations.size() - 1,
                favoritePage * FAVORITES_PER_PAGE + pagePosition
        );
        moveFavoriteReorderTo(targetIndex);
    }

    private void moveFavoriteReorderTo(int targetIndex) {
        List<FavoriteStation> stations = displayedStations();
        if (!StationOrder.move(stations, favoriteReorderIndex, targetIndex)) {
            highlightFavoriteReorderCard();
            return;
        }
        favoriteReorderIndex = targetIndex;
        favoritePage = targetIndex / FAVORITES_PER_PAGE;
        favoritePageManuallySelected = true;
        renderFavoritePage();
        favoritesList.post(this::highlightFavoriteReorderCard);
    }

    private void highlightFavoriteReorderCard() {
        clearFavoriteReorderHighlight();
        if (!favoriteReordering || favoriteReorderIndex < 0) {
            return;
        }
        int pagePosition = favoriteReorderIndex - favoritePage * FAVORITES_PER_PAGE;
        int childIndex = pagePosition - favoritesList.getFirstVisiblePosition();
        View card = favoritesList.getChildAt(childIndex);
        if (card == null) {
            favoritesList.postDelayed(this::highlightFavoriteReorderCard, 16L);
            return;
        }
        card.setAlpha(0.72f);
        card.setScaleX(1.05f);
        card.setScaleY(1.05f);
        card.setElevation(dp(18));
    }

    private void clearFavoriteReorderHighlight() {
        for (int index = 0; index < favoritesList.getChildCount(); index++) {
            View child = favoritesList.getChildAt(index);
            child.setAlpha(1f);
            child.setScaleX(1f);
            child.setScaleY(1f);
            child.setElevation(0f);
        }
    }

    private void finishFavoriteReorder(boolean announce) {
        cancelPendingFavoriteReorder();
        if (!favoriteReordering) {
            return;
        }
        clearFavoriteReorderHighlight();
        List<FavoriteStation> ordered = new ArrayList<>(displayedStations());
        if (showingFavorites) {
            favoriteStore.replaceOrder(ordered);
        } else {
            stationStore.replaceOrder(ordered);
        }
        favoriteReordering = false;
        favoriteReorderIndex = -1;
        favoriteTouchDownPosition = GridView.INVALID_POSITION;
        refreshFavorites();
        if (announce) {
            toast(tr("Kanavajärjestys tallennettu", "Station order saved"));
        }
    }


    private void updateFavoritePageIndicator(int pageCount) {
        if (renderedFavoritePage == favoritePage
                && renderedFavoritePageCount == pageCount) {
            return;
        }
        renderedFavoritePage = favoritePage;
        renderedFavoritePageCount = pageCount;
        favoritePageIndicator.removeAllViews();
        if (pageCount <= 1) {
            favoritePageIndicator.setVisibility(View.GONE);
            return;
        }

        favoritePageIndicator.setVisibility(View.VISIBLE);
        for (int index = 0; index < pageCount; index++) {
            final int page = index;
            FrameLayout touchTarget = new FrameLayout(this);
            LinearLayout.LayoutParams targetParams = new LinearLayout.LayoutParams(
                    dp(32),
                    LinearLayout.LayoutParams.MATCH_PARENT
            );

            boolean selected = index == favoritePage;
            View dot = new View(this);
            int dotSize = dp(selected ? 14 : 9);
            FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(
                    dotSize,
                    dotSize,
                    Gravity.CENTER
            );
            GradientDrawable dotBackground = new GradientDrawable();
            dotBackground.setShape(GradientDrawable.OVAL);
            if (selected) {
                dotBackground.setColor(Color.TRANSPARENT);
                dotBackground.setStroke(dp(2), getColor(R.color.text_secondary));
            } else {
                dotBackground.setColor(getColor(R.color.page_dot));
            }
            dot.setBackground(dotBackground);
            touchTarget.addView(dot, dotParams);
            touchTarget.setClickable(true);
            touchTarget.setFocusable(true);
            touchTarget.setContentDescription(getString(
                    R.string.station_page_description,
                    index + 1,
                    pageCount
            ));
            touchTarget.setOnClickListener(view -> {
                favoritePageManuallySelected = true;
                showFavoritePage(page);
            });
            favoritePageIndicator.addView(touchTarget, targetParams);
        }
    }

    private void scheduleFavoriteGridLayout(int stationCount) {
        if (favoritesList == null) {
            return;
        }
        favoritesList.post(() -> applyFavoriteGridLayout(stationCount));
    }

    private void applyFavoriteGridLayout(int stationCount) {
        int availableWidth = favoritesList.getWidth();
        int availableHeight = favoritesList.getHeight();
        if (availableWidth <= 0 || availableHeight <= 0) {
            return;
        }

        int minimumGap = Math.max(
                getResources().getDimensionPixelSize(R.dimen.station_tile_gap),
                dp(16)
        );
        int gap = clamp(
                Math.round(availableWidth * 0.02f),
                minimumGap,
                dp(26)
        );
        int minimumTileWidth = getResources().getDimensionPixelSize(
                R.dimen.station_tile_width
        );
        int targetFullRowWidth = Math.round(availableWidth * 0.88f);
        int tileWidth = clamp(
                (targetFullRowWidth - gap * (FAVORITES_PER_PAGE - 1))
                        / FAVORITES_PER_PAGE,
                minimumTileWidth,
                dp(168)
        );
        tileWidth = Math.min(
                tileWidth,
                Math.max(
                        1,
                        (availableWidth - gap * (FAVORITES_PER_PAGE - 1))
                                / FAVORITES_PER_PAGE
                )
        );
        int columnsByWidth = Math.max(
                1,
                (availableWidth + gap) / (tileWidth + gap)
        );
        int columns = Math.min(
                Math.min(FAVORITES_PER_PAGE, Math.max(1, stationCount)),
                columnsByWidth
        );
        columns = Math.max(1, columns);

        // Keep six presets per page, but scale their complete visual hierarchy
        // for wide car displays instead of leaving a narrow fixed-width strip.
        int minimumTileHeight = getResources().getDimensionPixelSize(
                R.dimen.station_tile_height
        );
        int maximumTileHeight = Math.max(1, Math.min(dp(176), availableHeight));
        int tileHeight = clamp(
                Math.round(tileWidth * 1.05f),
                Math.min(minimumTileHeight, maximumTileHeight),
                maximumTileHeight
        );
        int labelHeight = clamp(
                Math.round(tileHeight * 0.32f),
                getResources().getDimensionPixelSize(R.dimen.station_label_height),
                dp(54)
        );
        float nameTextSizePx = clamp(
                Math.round(tileWidth / 7.0f),
                sp(21),
                sp(26)
        );
        float monogramTextSizePx = clamp(
                Math.round(tileWidth / 4.7f),
                sp(28),
                sp(36)
        );
        int gridWidth = columns * tileWidth + Math.max(0, columns - 1) * gap;
        int horizontalPadding = Math.max(0, (availableWidth - gridWidth) / 2);
        int verticalPadding = Math.min(
                dp(32),
                Math.max(0, availableHeight - tileHeight)
        );

        favoritesList.setNumColumns(columns);
        favoritesList.setColumnWidth(tileWidth);
        favoritesList.setHorizontalSpacing(gap);
        favoritesList.setVerticalSpacing(gap);
        favoritesList.setPadding(
                horizontalPadding,
                verticalPadding,
                horizontalPadding,
                0
        );
        favoritesList.setStretchMode(GridView.NO_STRETCH);
        favoritesList.setGravity(Gravity.CENTER_HORIZONTAL);
        favoriteAdapter.setTileMetrics(
                tileHeight,
                labelHeight,
                nameTextSizePx,
                monogramTextSizePx
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int sp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().scaledDensity);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private String formatFrequency(int band, int frequency) {
        if (band < 3) {
            return FavoriteStation.formatFmFrequency(frequency) + " MHz";
        }
        return frequency + " kHz";
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String tr(String finnish, String english) {
        return AppLanguage.text(this, finnish, english);
    }

    private String stationName(String name) {
        return AppLanguage.stationName(this, name);
    }

    private void seedDebugPreview() {
        FavoriteStation[] previewStations = {
                new FavoriteStation(0, 92400, "Radio Suomi"),
                new FavoriteStation(0, 95500, "HitMix"),
                new FavoriteStation(0, 96800, "NRJ"),
                new FavoriteStation(0, 98100, "SUOMIPOP"),
                new FavoriteStation(0, 99700, "Paikallisradio"),
                new FavoriteStation(0, 101700, "YleX"),
                new FavoriteStation(0, 104400, "Radio Nova"),
                new FavoriteStation(0, 104500, "Loop"),
                new FavoriteStation(0, 106200, "Radio Rock")
        };
        if (stationStore.load().isEmpty()) {
            for (FavoriteStation station : previewStations) {
                stationStore.save(station);
            }
        }
        if (favoriteStore.load().isEmpty()) {
            favoriteStore.save(previewStations[0]);
            favoriteStore.save(previewStations[2]);
            favoriteStore.save(previewStations[3]);
        }
    }

    private void showDebugPreview() {
        onConnectionChanged(true, tr("Esikatselutila", "Preview mode"));
        setRadioControlsEnabled(true);
        onStateChanged(new RadioServiceClient.RadioState(
                0,
                98100,
                "Radio Suomipop",
                "BEHM — Frida",
                "Pop",
                true,
                true,
                false,
                false,
                false,
                false,
                true
        ));
        scanSummaryText.setText(tr(
                "Valmis automaattihakuun",
                "Ready for automatic scan"
        ));
    }
}
