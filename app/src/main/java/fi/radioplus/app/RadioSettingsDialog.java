package fi.radioplus.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Landscape settings with a stable category rail and large, descriptive controls. */
final class RadioSettingsDialog extends Dialog {
    interface Listener {
        void onLanguage();
        void onSensitivity();
        void onSteeringKeys();
        void onAbout();
        void onAutoStartChanged(boolean enabled);
    }

    private static final int WHITE = Color.rgb(240, 243, 247);
    private static final int MUTED = Color.rgb(160, 173, 189);
    private static final int PANEL = Color.rgb(23, 31, 42);
    private final Activity activity;
    private final Listener listener;
    private Boolean localMode;
    private int selectedCategory;
    private final LinearLayout content;
    private final TextView[] categories = new TextView[2];
    private final int accent;
    private final boolean compact;

    RadioSettingsDialog(Activity activity, Boolean localMode, Listener listener) {
        super(activity, R.style.RadioDialogTheme);
        this.activity = activity;
        this.listener = listener;
        this.localMode = localMode;
        accent = activity.getColor(R.color.accent);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        View viewport = activity.findViewById(android.R.id.content);
        int availableWidth = viewport.getWidth();
        int availableHeight = viewport.getHeight();
        if (availableWidth <= 0 || availableHeight <= 0) {
            availableWidth = activity.getResources().getDisplayMetrics().widthPixels;
            availableHeight = activity.getResources().getDisplayMetrics().heightPixels;
        }
        Rect visible = new Rect();
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
        if (!visible.isEmpty()) {
            availableWidth = Math.min(availableWidth, visible.width());
            availableHeight = Math.min(availableHeight, visible.height());
        }
        int width = Math.max(1, Math.min(dp(1120), availableWidth - dp(32)));
        int height = Math.max(1, Math.min(dp(520), availableHeight - dp(32)));
        compact = height < dp(460);
        boolean landscape = width >= dp(620);
        LinearLayout root = column();
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.setBackground(shape(Color.rgb(12, 18, 27), Color.rgb(62, 75, 92), 12));
        LinearLayout header = row();
        TextView title = text(R.string.action_setup, 28, WHITE);
        title.setTypeface(null, Typeface.BOLD);
        heading(title);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button close = new Button(activity);
        close.setId(R.id.settings_close);
        close.setText(R.string.settings_back);
        close.setTextSize(22);
        close.setTextColor(WHITE);
        close.setAllCaps(false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setPadding(0, 0, 0, 0);
        close.setMinHeight(0);
        close.setMinimumHeight(0);
        close.setMinWidth(0);
        close.setMinimumWidth(0);
        close.setStateListAnimator(null);
        close.setElevation(0f);
        close.setBackground(interactive());
        close.setOnClickListener(v -> dismiss());
        close.setMinimumWidth(dp(112));
        header.addView(close, new LinearLayout.LayoutParams(-2, dp(52)));
        root.addView(header);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.topMargin = dp(16);
        root.addView(line(), dividerParams);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        body.setPadding(0, dp(16), 0, 0);
        LinearLayout rail = new LinearLayout(activity);
        rail.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        int[] labels = {R.string.settings_radio, R.string.settings_general};
        for (int i = 0; i < labels.length; i++) {
            final int page = i;
            TextView tab = text(labels[i], landscape ? 23 : 20, MUTED);
            tab.setPadding(dp(16), 0, dp(12), 0);
            tab.setBackground(categoryBackground());
            tab.setFocusable(true);
            tab.setOnClickListener(v -> showCategory(page));
            LinearLayout.LayoutParams params = landscape
                    ? new LinearLayout.LayoutParams(-1, dp(64))
                    : new LinearLayout.LayoutParams(0, dp(64), 1);
            params.bottomMargin = dp(10);
            rail.addView(tab, params);
            categories[i] = tab;
        }
        if (landscape) {
            ScrollView railScroll = new ScrollView(activity);
            railScroll.addView(rail);
            body.addView(railScroll, new LinearLayout.LayoutParams(
                    Math.min(dp(210), width / 4), -1));
        } else {
            body.addView(rail, new LinearLayout.LayoutParams(-1, -2));
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.setClipToPadding(false);
        scroll.setPadding(landscape ? dp(24) : 0, 0, 0, dp(4));
        content = column();
        scroll.addView(content);
        body.addView(scroll, landscape ? new LinearLayout.LayoutParams(0, -1, 1)
                : new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        showCategory(0);
        setCanceledOnTouchOutside(false);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.84f);
            window.setLayout(width, height);
        }
    }

    private void showCategory(int index) {
        selectedCategory = index;
        for (int i = 0; i < categories.length; i++) {
            categories[i].setSelected(i == index);
            categories[i].setTextColor(i == index ? accent : MUTED);
            categories[i].setTypeface(null, i == index ? Typeface.BOLD : Typeface.NORMAL);
        }
        content.removeAllViews();
        if (index == 0) {
            section(R.string.settings_playback, R.string.settings_playback_hint);
            addToggle(R.id.settings_auto_start, R.string.settings_auto_start,
                    R.string.settings_auto_start_hint, AutoStartPreferences.isEnabled(activity),
                    listener::onAutoStartChanged);
            int sensitivity = localMode == null ? R.string.settings_unknown
                    : localMode ? R.string.settings_local : R.string.settings_dx;
            addAction(R.id.settings_sensitivity, R.string.settings_sensitivity, sensitivity,
                    listener::onSensitivity);
        } else {
            section(R.string.settings_general, R.string.settings_general_hint);
            addAction(R.id.settings_language, R.string.settings_language,
                    AppLanguage.displayName(activity),
                    listener::onLanguage);
            addAction(R.id.settings_steering, R.string.settings_steering,
                    R.string.settings_steering_hint, listener::onSteeringKeys);
            addAction(R.id.settings_about, R.string.settings_about,
                    activity.getString(R.string.about_version, BuildConfig.VERSION_NAME),
                    listener::onAbout);
        }
    }

    void updateLocalMode(Boolean mode) {
        if (java.util.Objects.equals(localMode, mode)) return;
        localMode = mode;
        if (selectedCategory == 0) showCategory(0);
    }

    private void section(int title, int subtitle) {
        TextView name = text(title, compact ? 24 : 26, WHITE);
        name.setTypeface(null, Typeface.BOLD);
        heading(name);
        content.addView(name);
        TextView hint = text(subtitle, compact ? 16 : 18, MUTED);
        hint.setPadding(0, dp(6), 0, dp(compact ? 12 : 18));
        content.addView(hint);
    }

    private LinearLayout label(int title, int subtitle) {
        LinearLayout label = column();
        label.addView(text(title, compact ? 22 : 24, WHITE));
        TextView hint = text(subtitle, compact ? 16 : 18, MUTED);
        hint.setPadding(0, dp(6), 0, 0);
        label.addView(hint);
        return label;
    }

    private LinearLayout card() {
        LinearLayout row = row();
        row.setPadding(dp(18), dp(compact ? 10 : 12), dp(18), dp(compact ? 10 : 12));
        row.setMinimumHeight(dp(compact ? 80 : 96));
        row.setBackground(interactive());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(compact ? 10 : 12);
        content.addView(row, params);
        return row;
    }

    private void addAction(int id, int title, int subtitle, Runnable callback) {
        addAction(id, title, activity.getString(subtitle), callback);
    }

    private void addAction(int id, int title, String subtitle, Runnable callback) {
        LinearLayout row = card();
        row.setId(id);
        row.setFocusable(true);
        LinearLayout labels = label(title, 0);
        ((TextView) labels.getChildAt(1)).setText(subtitle);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView chevron = text(0, 34, MUTED);
        chevron.setText("›");
        chevron.setGravity(Gravity.CENTER);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(80), dp(56)));
        // Keep the selected settings page underneath its child dialog so
        // the child's Back button returns here instead of to the radio.
        row.setOnClickListener(v -> callback.run());
    }

    private interface ToggleListener { void changed(boolean enabled); }

    private void addToggle(int id, int title, int subtitle, boolean checked, ToggleListener listener) {
        LinearLayout row = card();
        LinearLayout labels = label(title, subtitle);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(activity);
        toggle.setId(id);
        toggle.setContentDescription(activity.getString(title) + ". " + activity.getString(subtitle));
        toggle.setShowText(false);
        toggle.setSwitchMinWidth(dp(68));
        toggle.setThumbTintList(new ColorStateList(new int[][]{
                new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{accent, WHITE}));
        toggle.setTrackTintList(new ColorStateList(new int[][]{
                new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{accent, Color.rgb(91, 105, 124)}));
        toggle.setGravity(Gravity.CENTER);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((button, enabled) -> listener.changed(enabled));
        row.addView(toggle, new LinearLayout.LayoutParams(dp(80), dp(56)));
        row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.HORIZONTAL);
        // These are visual rows, not inline text: baseline alignment shifts
        // buttons and glyphs away from the actual row centre.
        view.setBaselineAligned(false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView text(int label, int size, int color) {
        TextView view = new TextView(activity);
        if (label != 0) view.setText(label);
        view.setTextSize(size);
        view.setIncludeFontPadding(false);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private void heading(TextView view) {
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
    }

    private View line() {
        View view = new View(activity);
        view.setBackgroundColor(Color.rgb(48, 60, 75));
        return view;
    }

    private GradientDrawable shape(int fill, int border, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        if (border != 0) background.setStroke(dp(1), border);
        return background;
    }

    private StateListDrawable interactive() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, shape(Color.rgb(38, 49, 60), accent, 8));
        states.addState(new int[]{android.R.attr.state_focused}, shape(PANEL, accent, 8));
        states.addState(new int[]{}, shape(PANEL, Color.rgb(43, 55, 70), 8));
        return states;
    }

    private StateListDrawable categoryBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected}, shape(Color.rgb(34, 46, 35), 0, 8));
        states.addState(new int[]{android.R.attr.state_pressed}, shape(PANEL, 0, 8));
        states.addState(new int[]{android.R.attr.state_focused}, shape(PANEL, accent, 8));
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return states;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
