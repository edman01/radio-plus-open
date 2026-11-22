package fi.radioplus.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A single scrolling list; the station being edited and Cancel stay in place. */
final class StationLogoDialog {
    private StationLogoDialog() {}

    static AlertDialog show(Activity activity, FavoriteStation station, boolean hasCustomLogo,
            Runnable pickFile, Consumer<String> setLogo) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        Context context = builder.getContext();
        View title = LayoutInflater.from(context).inflate(R.layout.station_logo_title, null);
        if (Build.VERSION.SDK_INT >= 28) title.findViewById(R.id.logo_dialog_title).setAccessibilityHeading(true);
        String name = AppLanguage.stationName(activity, station.name);
        ((TextView) title.findViewById(R.id.logo_dialog_station)).setText(
                name.isEmpty() ? station.frequencyLabel() : name);
        Choices choices = new Choices(context, station, name, hasCustomLogo);
        AlertDialog dialog = builder.setCustomTitle(title).setAdapter(choices, (ignored, which) -> {
            Choice item = choices.getItem(which);
            if (!item.enabled) return;
            if (item.kind == Kind.IMPORT) pickFile.run();
            else setLogo.accept(item.token);
        }).setNegativeButton(R.string.message_017, null).show();
        dialog.getListView().setDivider(null);
        Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        cancel.setAllCaps(false);
        cancel.setMinHeight(dp(context, 48));
        cancel.setTextSize(18);
        Window window = dialog.getWindow();
        View viewport = activity.findViewById(android.R.id.content);
        int available = viewport.getWidth();
        if (window != null && available > dp(context, 32)) {
            int maxHeight = dp(context, 480);
            int height = BuildConfig.BUNDLED_STATION_LOGOS && viewport.getHeight() > maxHeight + dp(context, 24)
                    ? maxHeight : ViewGroup.LayoutParams.WRAP_CONTENT;
            window.setLayout(Math.min(dp(context, 620), available - dp(context, 32)),
                    height);
        }
        return dialog;
    }

    private enum Kind { AUTO, IMPORT, REMOVE, HEADING, BUILTIN }

    private static final class Choice {
        final Kind kind;
        final String label, token;
        final int image;
        final boolean enabled, checked;

        Choice(Kind kind, String label, int image, String token, boolean enabled, boolean checked) {
            this.kind = kind; this.label = label; this.image = image; this.token = token;
            this.enabled = enabled; this.checked = checked;
        }
    }

    static final class Choices extends BaseAdapter {
        private final Context context;
        private final List<Choice> items = new ArrayList<>();
        private final LruCache<Integer, Bitmap> thumbnails = new LruCache<>(14);

        Choices(Context context, FavoriteStation station, String name, boolean custom) {
            this.context = context;
            boolean bundled = BuildConfig.BUNDLED_STATION_LOGOS;
            boolean hasArtwork = custom || StationLogoResolver.resolveForStation(
                    station.logo, custom, name.isEmpty(), name) != 0;
            if (bundled) {
                boolean automatic = !custom && !StationLogoResolver.NO_LOGO.equals(station.logo)
                        && StationLogoResolver.resolveExplicit(station.logo) == 0;
                items.add(new Choice(Kind.AUTO, context.getString(R.string.message_069),
                        R.drawable.ic_station_auto, "", true, automatic));
            }
            items.add(new Choice(Kind.IMPORT, context.getString(R.string.message_067),
                    R.drawable.ic_station_import, null, true, custom));
            items.add(new Choice(Kind.REMOVE, context.getString(R.string.message_068),
                    R.drawable.ic_station_remove, StationLogoResolver.NO_LOGO, hasArtwork, false));
            if (bundled && StationLogoResolver.BUILTIN_NAMES.length > 0) {
                items.add(new Choice(Kind.HEADING, context.getString(R.string.station_logo_library,
                        StationLogoResolver.BUILTIN_NAMES.length), 0, null, false, false));
                for (int i = 0; i < StationLogoResolver.BUILTIN_NAMES.length; i++) {
                    String token = StationLogoResolver.BUILTIN_TOKENS[i];
                    items.add(new Choice(Kind.BUILTIN, StationLogoResolver.BUILTIN_NAMES[i],
                            StationLogoResolver.resolveExplicit(token), token, true, token.equals(station.logo)));
                }
            }
        }

        @Override public int getCount() { return items.size(); }
        @Override public Choice getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return getItem(position).enabled; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return getItem(position).kind == Kind.HEADING ? 1 : 0; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Choice item = getItem(position);
            if (item.kind == Kind.HEADING) {
                TextView heading = recycled == null ? new TextView(context) : (TextView) recycled;
                heading.setText(item.label);
                heading.setTextSize(15);
                heading.setTextColor(context.getColor(R.color.text_secondary));
                heading.setBackgroundColor(0xff1a2230);
                heading.setPaddingRelative(dp(context,20), dp(context,8), dp(context,20), dp(context,4));
                if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
                return heading;
            }
            View row = recycled;
            if (row == null) {
                row = LayoutInflater.from(context).inflate(R.layout.row_station_logo_choice, parent, false);
                row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View v, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(v, info);
                        Choice choice = (Choice) v.getTag();
                        if (choice == null) return;
                        if (choice.kind != Kind.REMOVE) info.setSelected(choice.checked);
                        info.setEnabled(choice.enabled);
                    }
                });
            }
            row.setTag(item);
            row.setEnabled(item.enabled);
            row.setBackgroundColor(item.checked ? 0x24718c50 : Color.TRANSPARENT);
            TextView label = row.findViewById(R.id.choice_text);
            label.setText(item.label);
            label.setTextColor(context.getColor(item.enabled ? R.color.text_primary : R.color.text_muted));
            row.findViewById(R.id.choice_hint).setVisibility(item.kind == Kind.IMPORT ? View.VISIBLE : View.GONE);
            row.findViewById(R.id.choice_check).setVisibility(item.checked ? View.VISIBLE : View.INVISIBLE);
            ImageView image = row.findViewById(R.id.choice_icon);
            boolean artwork = item.kind == Kind.BUILTIN;
            int pad = dp(context, artwork ? 2 : 4);
            image.setPadding(pad, pad, pad, pad);
            image.setBackgroundColor(artwork ? Color.WHITE : Color.TRANSPARENT);
            image.setImageTintList(null);
            if (artwork) image.setImageBitmap(thumbnail(item.image));
            else {
                image.setImageResource(item.image);
                int tint = !item.enabled ? R.color.text_muted
                        : item.kind == Kind.REMOVE ? R.color.danger : R.color.text_secondary;
                image.setImageTintList(ColorStateList.valueOf(context.getColor(tint)));
            }
            return row;
        }

        private Bitmap thumbnail(int id) {
            if (id == 0) return null;
            Bitmap bitmap = thumbnails.get(id);
            if (bitmap != null) return bitmap;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(context.getResources(), id, options);
            int target = dp(context, 38);
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / (2 * options.inSampleSize) >= target) {
                options.inSampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            options.inScaled = false;
            bitmap = BitmapFactory.decodeResource(context.getResources(), id, options);
            if (bitmap != null) thumbnails.put(id, bitmap);
            return bitmap;
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }
}
