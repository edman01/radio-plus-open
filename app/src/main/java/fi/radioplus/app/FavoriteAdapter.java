package fi.radioplus.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.AbsListView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.io.File;

final class FavoriteAdapter extends BaseAdapter {
    private static final int MAX_RENDER_LOGO_DIMENSION = 512;

    interface Listener {
        void onTune(FavoriteStation station);

        void onRename(FavoriteStation station);

        void onDelete(FavoriteStation station);

        void onOptions(FavoriteStation station);

        void onToggleFavorite(FavoriteStation station);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final Listener listener;
    private final ArrayList<FavoriteStation> stations = new ArrayList<>();
    private final Set<String> favoriteKeys = new HashSet<>();
    private final LruCache<String, Bitmap> customLogoCache = new LruCache<>(12);
    private String currentKey = "";
    private int tileHeight;
    private int labelHeight;
    private float nameTextSizePx;
    private float monogramTextSizePx;

    FavoriteAdapter(Context context, Listener listener) {
        this.context = context;
        inflater = LayoutInflater.from(context);
        this.listener = listener;
    }

    void submit(
            List<FavoriteStation> newStations,
            String selectedKey,
            Set<String> newFavoriteKeys
    ) {
        stations.clear();
        stations.addAll(newStations);
        favoriteKeys.clear();
        if (newFavoriteKeys != null) {
            favoriteKeys.addAll(newFavoriteKeys);
        }
        currentKey = selectedKey == null ? "" : selectedKey;
        notifyDataSetChanged();
    }

    void setTileMetrics(
            int newTileHeight,
            int newLabelHeight,
            float newNameTextSizePx,
            float newMonogramTextSizePx
    ) {
        if (newTileHeight <= 0 || newLabelHeight <= 0
                || newNameTextSizePx <= 0f || newMonogramTextSizePx <= 0f) {
            return;
        }
        if (tileHeight == newTileHeight
                && labelHeight == newLabelHeight
                && Float.compare(nameTextSizePx, newNameTextSizePx) == 0
                && Float.compare(monogramTextSizePx, newMonogramTextSizePx) == 0) {
            return;
        }
        tileHeight = newTileHeight;
        labelHeight = newLabelHeight;
        nameTextSizePx = newNameTextSizePx;
        monogramTextSizePx = newMonogramTextSizePx;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return stations.size();
    }

    @Override
    public FavoriteStation getItem(int position) {
        return stations.get(position);
    }

    @Override
    public long getItemId(int position) {
        FavoriteStation station = getItem(position);
        return (station.band * 1_000_000L) + station.frequency;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.row_favorite, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        if (tileHeight > 0) {
            ViewGroup.LayoutParams layoutParams = convertView.getLayoutParams();
            if (layoutParams == null) {
                layoutParams = new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        tileHeight
                );
            } else {
                layoutParams.height = tileHeight;
            }
            convertView.setLayoutParams(layoutParams);
        }
        if (labelHeight > 0) {
            ViewGroup.LayoutParams labelLayoutParams = holder.name.getLayoutParams();
            labelLayoutParams.height = labelHeight;
            holder.name.setLayoutParams(labelLayoutParams);
        }
        if (nameTextSizePx > 0f) {
            holder.name.setTextSize(TypedValue.COMPLEX_UNIT_PX, nameTextSizePx);
        }
        if (monogramTextSizePx > 0f) {
            holder.monogram.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    monogramTextSizePx
            );
        }

        FavoriteStation station = getItem(position);
        convertView.setAlpha(1f);
        convertView.setScaleX(1f);
        convertView.setScaleY(1f);
        convertView.setElevation(0f);
        boolean selected = station.key().equals(currentKey);
        boolean favorite = favoriteKeys.contains(station.key());
        boolean unnamed = station.name.isEmpty();
        String displayName = unnamed
                ? AppLanguage.text(context, "Nimeä ", "Name ")
                        + compactFrequency(station)
                : AppLanguage.stationName(context, station.name);
        holder.name.setText(displayName);
        File customLogo = FavoriteLogoStore.fileForToken(
                holder.itemView.getContext(),
                station.logo
        );
        Bitmap customBitmap = null;
        if (customLogo != null && customLogo.isFile()) {
            String path = customLogo.getAbsolutePath();
            customBitmap = customLogoCache.get(path);
            if (customBitmap == null) {
                customBitmap = decodeCustomLogo(path);
                if (customBitmap != null) {
                    customLogoCache.put(path, customBitmap);
                }
            }
        }
        boolean hasCustomLogo = customBitmap != null;
        int logoResource = StationLogoResolver.resolveForStation(
                station.logo,
                hasCustomLogo,
                unnamed,
                displayName
        );
        boolean hasLogo = hasCustomLogo || logoResource != 0;
        holder.logo.setImageDrawable(null);
        holder.logo.setBackgroundColor(Color.TRANSPARENT);
        // User imports may be small (for example 160x120 station logos).
        // Fit them without cropping or enlarging beyond their source pixels.
        // Reset on every bind because grid views are recycled.
        holder.logo.setScaleType(hasCustomLogo
                ? ImageView.ScaleType.CENTER_INSIDE : ImageView.ScaleType.FIT_CENTER);
        // A discovered RDS name remains visible even when no matching artwork
        // exists. The empty artwork tile follows the OEM "Tyhjä" treatment.
        holder.name.setVisibility(View.VISIBLE);
        // Favorite edits have one entry point: the station's long-press menu.
        holder.favoriteToggle.setVisibility(View.GONE);
        holder.art.setBackgroundResource(R.drawable.bg_station_art);
        if (hasCustomLogo) {
            holder.monogram.setVisibility(View.GONE);
            holder.logo.setVisibility(View.VISIBLE);
            holder.logo.setImageBitmap(customBitmap);
        } else if (logoResource != 0) {
            holder.monogram.setVisibility(View.GONE);
            holder.logo.setVisibility(View.VISIBLE);
            if (StationLogoResolver.needsWhiteBackground(logoResource)) {
                holder.logo.setBackgroundColor(Color.WHITE);
            }
            holder.logo.setImageResource(logoResource);
        } else {
            holder.logo.setVisibility(View.GONE);
            holder.monogram.setVisibility(View.VISIBLE);
            holder.monogram.setText(AppLanguage.text(context, "Tyhjä", "Empty"));
            holder.monogram.setTextColor(
                    holder.itemView.getContext().getColor(R.color.text_primary)
            );
            holder.monogram.setTypeface(null, Typeface.NORMAL);
        }
        holder.name.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        holder.name.setTextColor(holder.itemView.getContext().getColor(
                selected ? R.color.accent : R.color.text_primary
        ));
        holder.frequency.setText(holder.itemView.getContext().getString(
                R.string.station_meta,
                station.bandLabel(),
                station.frequencyLabel()
        ));
        holder.itemView.setSelected(selected);
        holder.selectedOutline.setVisibility(selected ? View.VISIBLE : View.GONE);
        holder.itemView.setContentDescription(
                displayName + ", " + station.frequencyLabel()
                        + (favorite
                        ? AppLanguage.text(context, ", suosikki.", ", favorite.")
                        : AppLanguage.text(context, ", ei suosikki.", ", not a favorite."))
                        + (unnamed
                        ? AppLanguage.text(
                                context,
                                " Napauta kuunnellaksesi ja nimetäksesi.",
                                " Tap to listen and name the station."
                        )
                        : AppLanguage.text(
                                context,
                                " Paina pitkään muokataksesi.",
                                " Touch and hold to edit."
                        ))
        );
        holder.favoriteToggle.setImageResource(R.drawable.ic_star_outline_skoda);
        holder.favoriteToggle.setContentDescription(holder.itemView.getContext().getString(
                R.string.content_add_favorite
        ));
        // MainActivity owns the complete preset touch surface so horizontal
        // swipes can change pages without a card consuming the gesture first.
        holder.itemView.setOnClickListener(null);
        holder.itemView.setOnLongClickListener(null);
        holder.itemView.setClickable(false);
        holder.itemView.setLongClickable(false);
        holder.favoriteToggle.setClickable(false);
        holder.favoriteToggle.setFocusable(false);
        holder.edit.setOnClickListener(view -> listener.onRename(station));
        holder.delete.setOnClickListener(view -> listener.onDelete(station));
        return convertView;
    }

    private static String compactFrequency(FavoriteStation station) {
        return station.frequencyLabel()
                .replace(" MHz", "")
                .replace(" kHz", "");
    }

    private static Bitmap decodeCustomLogo(String path) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        int largestSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (largestSide / options.inSampleSize > MAX_RENDER_LOGO_DIMENSION) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        if (bitmap != null) {
            // Imported pixel dimensions are not density-independent resources.
            bitmap.setDensity(Bitmap.DENSITY_NONE);
        }
        return bitmap;
    }

    private static final class ViewHolder {
        final View itemView;
        final View art;
        final ImageView logo;
        final TextView monogram;
        final TextView name;
        final TextView frequency;
        final ImageButton edit;
        final ImageButton delete;
        final ImageButton favoriteToggle;
        final View selectedOutline;

        ViewHolder(View itemView) {
            this.itemView = itemView;
            art = itemView.findViewById(R.id.favorite_art);
            logo = itemView.findViewById(R.id.favorite_logo);
            monogram = itemView.findViewById(R.id.favorite_monogram);
            name = itemView.findViewById(R.id.favorite_name);
            frequency = itemView.findViewById(R.id.favorite_frequency);
            edit = itemView.findViewById(R.id.favorite_edit);
            delete = itemView.findViewById(R.id.favorite_delete);
            favoriteToggle = itemView.findViewById(R.id.favorite_toggle);
            selectedOutline = itemView.findViewById(R.id.favorite_selected_outline);
        }
    }
}
