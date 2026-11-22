package fi.radioplus.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Native controls with one focusable touch target per setting. Sizes are never scaled below car minima. */
final class CarSettingsViews {
    static final int BASE = 0xff10151c, SURFACE = 0xff1b2530, NAV = 0xff263440,
            PRESSED = 0xff334554, PRIMARY = 0xfff4f7f8, SECONDARY = 0xffc7d1da,
            LINE = 0xff43525e, INDICATOR = 0xffcad5df;
    private final Activity a;
    final int accent;
    final LinearLayout root, body;
    final ScrollView scroll;
    final Button nav;
    final TextView title;
    private final boolean showNavLabel;

    CarSettingsViews(Activity activity, Runnable navigate) {
        a = activity;
        accent = a.getColor(R.color.accent);
        FrameLayout canvas = new FrameLayout(a);
        canvas.setBackgroundColor(BASE);
        root = column();
        root.setFocusableInTouchMode(true);
        FrameLayout.LayoutParams shell = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL);
        canvas.addView(root, shell);
        canvas.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            int width = Math.min(r-l, dp(1120));
            if (width > 0 && root.getLayoutParams().width != width) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)root.getLayoutParams();
                lp.width=width; root.setLayoutParams(lp);
            }
        });
        int widthDp = a.getResources().getConfiguration().screenWidthDp;
        float scale = a.getResources().getConfiguration().fontScale;
        showNavLabel = widthDp >= 590 && scale <= 1.35f;
        int edge = dp(widthDp < 460 ? 16 : 24);
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBaselineAligned(false);
        bar.setPadding(edge,dp(8),edge,dp(8));
        root.addView(bar,-1,-2);
        nav = new Button(a);
        style(nav, NAV);
        nav.setId(R.id.settings_close);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(showNavLabel ? 16 : 22),0,dp(showNavLabel ? 16 : 22),0);
        nav.setOnClickListener(v -> navigate.run());
        bar.addView(nav,new LinearLayout.LayoutParams(showNavLabel ? -2 : dp(80),-2));
        title = text("",36,PRIMARY,true);
        title.setId(R.id.car_settings_title);
        heading(title);
        LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(0,-2,1);
        titleSize.setMarginStart(dp(widthDp < 460 ? 12 : 24));
        titleSize.setMarginEnd(dp(8));
        bar.addView(title,titleSize);
        ImageView brand = new ImageView(a);
        brand.setImageResource(R.drawable.car_radio_mark);
        brand.setScaleType(ImageView.ScaleType.FIT_CENTER);
        brand.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        bar.addView(brand,dp(widthDp < 460 ? 44 : 56),dp(widthDp < 460 ? 44 : 56));
        View divider = new View(a); divider.setBackgroundColor(LINE);
        root.addView(divider,-1,dp(1));
        scroll = new ScrollView(a);
        scroll.setId(R.id.car_settings_scroll);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setSaveEnabled(false); // the activity restores scroll per navigation level
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        body = column(); body.setId(R.id.car_settings_body);
        body.setPadding(edge,dp(24),edge,dp(24));
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        a.setContentView(canvas);
    }

    void header(int heading, boolean atRoot) {
        title.setText(heading);
        String name = a.getString(atRoot ? R.string.settings_close : R.string.settings_navigate_back);
        nav.setText(showNavLabel ? name : "");
        nav.setContentDescription(name);
        nav.setCompoundDrawablesRelativeWithIntrinsicBounds(atRoot ? R.drawable.car_close : R.drawable.car_back,0,0,0);
        nav.setCompoundDrawablePadding(showNavLabel ? dp(8) : 0);
        if (Build.VERSION.SDK_INT >= 28) body.setAccessibilityPaneTitle(a.getString(heading));
    }

    void section(int label) {
        TextView name = text(a.getString(label),24,SECONDARY,true);
        heading(name); add(name,24);
    }

    TextView description(String value) {
        TextView t=text(value,24,SECONDARY,false); add(t,24); return t;
    }

    Button action(int id, String label, String summary, boolean forward, Runnable click) {
        Button item = new Button(a); style(item,SURFACE);
        setId(item,id); item.setText(caption(label,summary));
        if (forward) item.setCompoundDrawablesRelativeWithIntrinsicBounds(0,0,R.drawable.car_next,0);
        item.setCompoundDrawablePadding(dp(16));
        item.setOnClickListener(v -> click.run()); add(item,24); return item;
    }

    Switch toggle(int id, String label, String summary, boolean initial, android.widget.CompoundButton.OnCheckedChangeListener click) {
        Switch item=new Switch(a); style(item,SURFACE); setId(item,id);
        item.setText(caption(label,summary)); item.setShowText(false); item.setSplitTrack(false);
        int[][] states = {new int[]{android.R.attr.state_checked},new int[]{}};
        GradientDrawable track=shape(INDICATOR,0,20); track.setSize(dp(56),dp(28));
        track.setColor(new ColorStateList(states,new int[]{accent,INDICATOR}));
        GradientDrawable thumb=new GradientDrawable(); thumb.setShape(GradientDrawable.OVAL);
        thumb.setSize(dp(22),dp(22)); thumb.setColor(BASE);
        LayerDrawable slot=new LayerDrawable(new Drawable[]{thumb});
        int rim=dp(3); slot.setLayerInset(0,rim,rim,rim,rim);
        item.setTrackTintList(null); item.setThumbTintList(null);
        item.setTrackDrawable(track); item.setThumbDrawable(slot);
        item.setSwitchMinWidth(dp(56)); item.setSwitchPadding(dp(16));
        item.setChecked(initial); item.setOnCheckedChangeListener(click); add(item,24); return item;
    }

    RadioButton choice(String label, boolean selected, Runnable click) {
        RadioButton opt=new RadioButton(a); style(opt,SURFACE);
        opt.setText(label); opt.setChecked(selected);
        opt.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{accent,INDICATOR}));
        opt.setOnClickListener(v -> click.run()); add(opt,24); return opt;
    }

    CharSequence caption(String label, String summary) {
        if (summary==null || summary.isEmpty()) return label;
        SpannableStringBuilder text=new SpannableStringBuilder(label).append('\n').append(summary);
        int start=label.length()+1;
        text.setSpan(new AbsoluteSizeSpan(typePx(24)),start,text.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(SECONDARY),start,text.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private void add(View v,int gap) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=body.getChildCount()==0 ? 0 : dp(gap);
        body.addView(v,p);
    }
    private void style(TextView v,int fill) {
        if (v instanceof Button) ((Button)v).setAllCaps(false);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX,typePx(32)); v.setTextColor(PRIMARY);
        v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        v.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        v.setSingleLine(false); v.setIncludeFontPadding(false);
        v.setMinHeight(dp(80)); v.setMinimumHeight(dp(80));
        v.setMinWidth(dp(80)); v.setMinimumWidth(dp(80));
        v.setLetterSpacing(0); v.setStateListAnimator(null);
        v.setPaddingRelative(dp(18),dp(12),dp(18),dp(12));
        v.setBackgroundTintList(null);
        StateListDrawable bg=new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused},shape(fill,accent,12));
        bg.addState(new int[]{android.R.attr.state_pressed},shape(PRESSED,accent,12));
        bg.addState(new int[]{},shape(fill,fill==NAV ? INDICATOR : LINE,12));
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18ffffff),bg,null));
    }
    private void setId(View v,int id) {if(id!=View.NO_ID)v.setId(id);}
    private TextView text(String value,int size,int color,boolean medium) {
        TextView v=new TextView(a); v.setText(value); v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX,typePx(size)); v.setIncludeFontPadding(false);
        v.setTypeface(Typeface.create(medium?"sans-serif-medium":"sans-serif",Typeface.NORMAL)); return v;
    }
    private void heading(View view) { if(Build.VERSION.SDK_INT>=28)view.setAccessibilityHeading(true); }
    private LinearLayout column(){LinearLayout v=new LinearLayout(a); v.setOrientation(LinearLayout.VERTICAL); return v;}
    private GradientDrawable shape(int fill,int edge,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));
        if(edge!=0)d.setStroke(dp(edge==accent?2:1),edge);return d;
    }
    int dp(int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    int typePx(int n){return Math.max(dp(n),Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,n,a.getResources().getDisplayMetrics())));}
}
