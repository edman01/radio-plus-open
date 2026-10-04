package fi.radioplus.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Station management is opened by holding a station card, never from Settings. */
final class StationOptionsDialog extends Dialog {
    interface Listener { void selected(int action); }
    private final Activity activity;
    private final int accent;

    StationOptionsDialog(Activity activity, String name, String frequency, String[] actions,
            boolean favorite, Listener listener) {
        super(activity, R.style.RadioDialogTheme);
        this.activity = activity;
        accent = activity.getColor(R.color.accent);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = column();
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.setBackground(shape(0xff0c121b, 0xff3e4b5c));
        LinearLayout header = row();
        LinearLayout titles = column();
        TextView title = text(name, 26, 0xfff0f3f7);
        title.setTypeface(null, Typeface.BOLD);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        titles.addView(title);
        if (!name.equals(frequency)) titles.addView(text(frequency, 18, 0xffa0adbd));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = button(activity.getString(R.string.settings_close), 0xfff0f3f7);
        close.setOnClickListener(v -> dismiss());
        close.setMinimumWidth(dp(112));
        header.addView(close, new LinearLayout.LayoutParams(-2, dp(52)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(activity);
        divider.setBackgroundColor(0xff303c4b);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(1));
        line.topMargin = dp(16);
        line.bottomMargin = dp(16);
        root.addView(divider, line);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout choices = column();
        for (int i = 0; i < actions.length; i += 2) {
            LinearLayout pair = row();
            for (int j = i; j < Math.min(i + 2, actions.length); j++) {
                final int action = j;
                Button control = button(actions[j], j == 0 ? (favorite ? 0xffff9292 : accent) : 0xfff0f3f7);
                control.setOnClickListener(v -> { dismiss(); listener.selected(action); });
                control.setMinHeight(dp(72));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                if (j == i) params.rightMargin = dp(12);
                pair.addView(control, params);
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(12);
            choices.addView(pair, params);
        }
        scroll.addView(choices);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        Rect visible = new Rect();
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
        View viewport = activity.findViewById(android.R.id.content);
        int width = Math.max(1, Math.min(dp(880), Math.min(viewport.getWidth(), visible.width()) - dp(32)));
        int height = Math.max(1, Math.min(dp(340), Math.min(viewport.getHeight(), visible.height()) - dp(32)));
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.8f);
            window.setLayout(width, height);
        }
    }

    private Button button(String label, int color) {
        Button button = new Button(activity);
        button.setText(label);
        button.setTextSize(22);
        button.setTextColor(color);
        button.setAllCaps(false);
        button.setIncludeFontPadding(false);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_pressed}, shape(0xff26313c, accent));
        background.addState(new int[]{android.R.attr.state_focused}, shape(0xff171f2a, accent));
        background.addState(new int[]{}, shape(0xff171f2a, 0xff364354));
        button.setBackground(background);
        return button;
    }
    private TextView text(String label, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(label);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setIncludeFontPadding(false);
        return text;
    }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setBaselineAligned(false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private GradientDrawable shape(int fill, int border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setStroke(dp(1), border);
        shape.setCornerRadius(dp(8));
        return shape;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
