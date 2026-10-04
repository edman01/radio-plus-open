package fi.radioplus.app;

import android.app.Activity;
import android.app.Dialog;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Window;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Opt-in RAM-only input trace. Does not change routing or request permissions. */
final class SteeringDiagnosticsDialog extends Dialog {
    private final Activity activity;
    private final boolean preview;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView state;
    private final TextView trace;
    private final Button previous;
    private final Button next;
    private final Button start;
    private final Button stop;
    private Runnable onClosed = () -> { };
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!isShowing()) return;
            render();
            handler.postDelayed(this, 300L);
        }
    };

    SteeringDiagnosticsDialog(Activity activity, boolean preview) {
        super(activity, R.style.RadioDialogTheme);
        this.activity = activity;
        this.preview = preview;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(12));
        root.setBackgroundColor(Color.rgb(12, 18, 27));
        LinearLayout header = row();
        TextView title = text(activity.getString(R.string.steering_test_title), 24);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button close = button(activity.getString(R.string.settings_back), this::dismiss);
        header.addView(close);
        root.addView(header);
        root.addView(text(activity.getString(R.string.steering_test_instructions), 16));

        LinearLayout actions = row();
        start = button(activity.getString(R.string.steering_test_start), () -> {
            SteeringDiagnosticTrace.get().begin();
            render();
        });
        stop = button(tr("Lopeta", "Stop"), () -> {
            SteeringDiagnosticTrace.get().stop();
            render();
        });
        previous = button(activity.getString(R.string.steering_test_previous), () -> testSkip(false));
        next = button(activity.getString(R.string.steering_test_next), () -> testSkip(true));
        for (Button button : new Button[]{start, stop, previous, next}) {
            actions.addView(button, new LinearLayout.LayoutParams(0, dp(56), 1));
        }
        root.addView(actions);
        state = text("", 15);
        state.setTypeface(Typeface.MONOSPACE);
        root.addView(state);
        ScrollView scroll = new ScrollView(activity);
        trace = text("", 15);
        trace.setTypeface(Typeface.MONOSPACE);
        trace.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(trace);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        setCanceledOnTouchOutside(false);
        setOnShowListener(ignored -> {
            SteeringDiagnosticTrace.get().clear();
            resize();
            refresh.run();
        });
        setOnDismissListener(ignored -> {
            handler.removeCallbacksAndMessages(null);
            SteeringDiagnosticTrace.get().clear();
            onClosed.run();
        });
    }

    void setOnClosed(Runnable callback) { onClosed = callback; }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null) {
            SteeringDiagnosticTrace.get().key("test-window", event.getKeyCode(),
                    event.getAction(), event.getMetaState(), event.getRepeatCount(),
                    event.getScanCode(), event.getFlags(), event.getSource(), event.getDownTime(), event.getEventTime());
            // Identical filter to MainActivity: never turn arbitrary letters/DPAD into skips.
            if (!preview && RadioPlaybackService.supportsMediaKey(event.getKeyCode())
                    && RadioMediaButtonReceiver.dispatch(activity, event)) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) SteeringDiagnosticTrace.get().stop();
    }

    @Override protected void onStop() {
        handler.removeCallbacksAndMessages(null);
        SteeringDiagnosticTrace.get().clear();
        super.onStop();
    }

    private void testSkip(boolean forward) {
        SteeringDiagnosticTrace.get().event("test-button", forward ? "next" : "previous");
        if (preview) {
            SteeringDiagnosticTrace.get().event("test-button", "preview: hardware dispatch disabled");
        } else {
            try {
                RadioPlaybackService.skipStation(activity, forward);
            } catch (RuntimeException error) {
                SteeringDiagnosticTrace.get().event("test-button", "service-start-failed");
            }
        }
        render();
    }

    // Stable English key=value labels are diagnostic protocol, not localized UI labels.
    @SuppressLint("SetTextI18n")
    private void render() {
        SteeringDiagnosticTrace capture = SteeringDiagnosticTrace.get();
        boolean recording = capture.isRecording();
        previous.setEnabled(recording);
        next.setEnabled(recording);
        stop.setEnabled(recording);
        start.setEnabled(!recording);
        StationNavigationStore navigation = new StationNavigationStore(activity);
        state.setText("Radio+ " + BuildConfig.VERSION_NAME
                + " | " + (recording ? activity.getString(R.string.steering_test_recording) + " "
                + capture.remainingSeconds() + " s" : activity.getString(R.string.steering_test_stopped))
                + "\na11y enabled=" + accessibilityEnabled()
                + " connected=" + SteeringKeyService.isConnected()
                + " filterRequested=" + SteeringKeyService.isFilterRequested()
                + "\n" + RadioPlaybackService.steeringDiagnosticStatus()
                + "\nlist=" + (navigation.favoritesSelected() ? "favorites" : "stations")
                + " count=" + navigation.load().size()
                + "\n" + activity.getString(R.string.steering_test_unknown_target));
        trace.setText(capture.report());
    }

    private boolean accessibilityEnabled() {
        AccessibilityManager manager = (AccessibilityManager)
                activity.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        String expected = new ComponentName(activity, SteeringKeyService.class).flattenToString();
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            ComponentName component = ComponentName.unflattenFromString(info.getId());
            if (component != null && expected.equals(component.flattenToString())) return true;
        }
        return false;
    }

    private void resize() {
        Rect visible = new Rect();
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        if (!visible.isEmpty()) { width = Math.min(width, visible.width()); height = Math.min(height, visible.height()); }
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(Math.max(1, Math.min(dp(1150), width - dp(24))),
                    Math.max(1, Math.min(dp(620), height - dp(24))));
        }
    }

    private LinearLayout row() {
        LinearLayout result = new LinearLayout(activity);
        result.setOrientation(LinearLayout.HORIZONTAL);
        result.setGravity(Gravity.CENTER_VERTICAL);
        result.setBaselineAligned(false);
        return result;
    }

    private TextView text(String value, int size) {
        TextView result = new TextView(activity);
        result.setText(value);
        result.setTextColor(Color.rgb(235, 240, 246));
        result.setTextSize(size);
        return result;
    }

    private Button button(String label, Runnable action) {
        Button result = new Button(activity);
        result.setText(label);
        result.setAllCaps(false);
        result.setTextSize(17);
        result.setOnClickListener(ignored -> action.run());
        return result;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private String tr(String fi, String en) { return AppLanguage.text(activity, fi, en); }
}
