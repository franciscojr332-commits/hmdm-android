/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmdm.launcher.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.hmdm.launcher.Const;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Persistent cache for the launcher background image.
 * Image is stored in app files dir and only replaced when MDM sends a new backgroundImageUrl.
 * This keeps the background visible when the device is offline.
 */
public final class BackgroundImageCache {

    private static final String FILE_NAME = "launcher_background.jpg";

    private BackgroundImageCache() {
    }

    /**
     * Returns the cached background file if it exists and corresponds to the given URL.
     * Use this to display the background from local storage (no network).
     */
    public static File getCachedFile(Context context, String imageUrl) {
        if (context == null || imageUrl == null || imageUrl.isEmpty()) {
            return null;
        }
        String cachedUrl = context.getSharedPreferences(Const.PREFERENCES, Context.MODE_PRIVATE)
                .getString(Const.PREFERENCES_CACHED_BACKGROUND_IMAGE_URL, null);
        if (cachedUrl == null || !cachedUrl.equals(imageUrl)) {
            return null;
        }
        File file = new File(context.getFilesDir(), FILE_NAME);
        return file.exists() ? file : null;
    }

    /**
     * If the current cached image is for a different URL, deletes the old file so a new one will be fetched.
     * Call this before loading so that when MDM changes the background URL we clear the old image.
     */
    public static void clearIfUrlChanged(Context context, String newUrl) {
        if (context == null || newUrl == null || newUrl.isEmpty()) {
            return;
        }
        String cachedUrl = context.getSharedPreferences(Const.PREFERENCES, Context.MODE_PRIVATE)
                .getString(Const.PREFERENCES_CACHED_BACKGROUND_IMAGE_URL, null);
        if (cachedUrl != null && !cachedUrl.equals(newUrl)) {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (file.exists() && !file.delete()) {
                Log.w(Const.LOG_TAG, "BackgroundImageCache: could not delete old background file");
            }
            context.getSharedPreferences(Const.PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .remove(Const.PREFERENCES_CACHED_BACKGROUND_IMAGE_URL)
                    .apply();
        }
    }

    /**
     * Returns the file where the background image should be saved (for writing).
     */
    public static File getBackgroundFile(Context context) {
        return context == null ? null : new File(context.getFilesDir(), FILE_NAME);
    }

    /**
     * Saves the bitmap to persistent storage and records the URL so we only re-fetch when URL changes.
     */
    public static void save(Context context, String imageUrl, Bitmap bitmap) {
        if (context == null || imageUrl == null || imageUrl.isEmpty() || bitmap == null) {
            return;
        }
        File file = getBackgroundFile(context);
        if (file == null) {
            return;
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            fos.flush();
            context.getSharedPreferences(Const.PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .putString(Const.PREFERENCES_CACHED_BACKGROUND_IMAGE_URL, imageUrl)
                    .apply();
            Log.d(Const.LOG_TAG, "BackgroundImageCache: saved background for URL (length=" + imageUrl.length() + ")");
        } catch (IOException e) {
            Log.e(Const.LOG_TAG, "BackgroundImageCache: failed to save background", e);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
