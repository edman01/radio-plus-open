package fi.radioplus.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Imports a user-selected station logo into app-private storage.
 *
 * <p>The source URI is intentionally not kept. Some Android file providers
 * revoke their URI grant after a reboot; an app-private normalized copy keeps
 * the preset logo reliable in a head unit.</p>
 */
final class FavoriteLogoStore {
    private static final String DIRECTORY = "station_logos";
    private static final int MAX_SOURCE_DIMENSION = 2048;
    private static final int OUTPUT_DIMENSION = 512;

    private FavoriteLogoStore() {
    }

    static String importLogo(
            Context context,
            FavoriteStation station,
            Uri source
    ) throws IOException {
        if (source == null) {
            throw new IOException("Logo URI is missing");
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            if (input == null) {
                throw new IOException("Logo cannot be opened");
            }
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Selected file is not a supported image");
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        int largestSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (largestSide / options.inSampleSize > MAX_SOURCE_DIMENSION) {
            options.inSampleSize *= 2;
        }

        Bitmap decoded;
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            if (input == null) {
                throw new IOException("Logo cannot be opened");
            }
            decoded = BitmapFactory.decodeStream(input, null, options);
        }
        if (decoded == null) {
            throw new IOException("Selected image could not be decoded");
        }

        Bitmap output = decoded;
        int decodedLargestSide = Math.max(decoded.getWidth(), decoded.getHeight());
        if (decodedLargestSide > OUTPUT_DIMENSION) {
            float scale = OUTPUT_DIMENSION / (float) decodedLargestSide;
            output = Bitmap.createScaledBitmap(
                    decoded,
                    Math.max(1, Math.round(decoded.getWidth() * scale)),
                    Math.max(1, Math.round(decoded.getHeight() * scale)),
                    true
            );
        }

        File directory = context.getDir(DIRECTORY, Context.MODE_PRIVATE);
        String fileName = "station_" + station.band + "_" + station.frequency + ".png";
        File destination = new File(directory, fileName);
        File temporary = new File(directory, fileName + ".tmp");
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary, false)) {
                if (!output.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new IOException("Logo could not be encoded");
                }
                stream.getFD().sync();
            }
            try {
                Files.move(
                        temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            if (temporary.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
            if (output != decoded) {
                output.recycle();
            }
            decoded.recycle();
        }
        return StationLogoResolver.FILE_PREFIX + fileName;
    }

    static File fileForToken(Context context, String logoToken) {
        if (logoToken == null || !logoToken.startsWith(StationLogoResolver.FILE_PREFIX)) {
            return null;
        }
        String fileName = logoToken.substring(StationLogoResolver.FILE_PREFIX.length());
        if (!fileName.matches("[A-Za-z0-9_.-]+")) {
            return null;
        }
        File directory = context.getDir(DIRECTORY, Context.MODE_PRIVATE);
        File candidate = new File(directory, fileName);
        try {
            String directoryPath = directory.getCanonicalPath() + File.separator;
            if (!candidate.getCanonicalPath().startsWith(directoryPath)) {
                return null;
            }
        } catch (IOException ignored) {
            return null;
        }
        return candidate;
    }

    static void delete(Context context, String logoToken) {
        File file = fileForToken(context, logoToken);
        if (file != null && file.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
