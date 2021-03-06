/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is Mozilla Android code.
 *
 * The Initial Developer of the Original Code is Mozilla Foundation.
 * Portions created by the Initial Developer are Copyright (C) 2011-2012
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 *   Gian-Carlo Pascutto <gpascutto@mozilla.com>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.mozilla.gecko;

import org.mozilla.gecko.db.BrowserContract;
import org.mozilla.gecko.db.BrowserContract.Bookmarks;
import org.mozilla.gecko.db.BrowserContract.History;
import org.mozilla.gecko.db.BrowserContract.ImageColumns;
import org.mozilla.gecko.db.BrowserContract.Images;
import org.mozilla.gecko.db.BrowserContract.Passwords;
import org.mozilla.gecko.db.BrowserContract.URLColumns;
import org.mozilla.gecko.db.BrowserContract.SyncColumns;
import org.mozilla.gecko.sqlite.SQLiteBridge;
import org.mozilla.gecko.sqlite.SQLiteBridgeException;
import org.mozilla.gecko.sync.setup.SyncAccounts;
import org.mozilla.gecko.sync.setup.SyncAccounts.SyncAccountParameters;

import android.accounts.Account;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.ContentProviderResult;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteConstraintException;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.provider.Browser;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

public class ProfileMigrator {
    private static final String LOGTAG = "ProfileMigrator";
    private static final String PREFS_NAME = "ProfileMigrator";
    private File mProfileDir;
    private ContentResolver mCr;
    private Context mContext;

    // Default number of history entries to migrate in one run.
    private static final int DEFAULT_HISTORY_MIGRATE_COUNT = 2000;

    // Maximum number of history entries to fetch at once.
    // This limits the max memory use to about 10M (empirically), so we don't OOM.
    private static final int HISTORY_MAX_BATCH = 5000;

    private static final String PREFS_MIGRATE_BOOKMARKS_DONE = "bookmarks_done";
    private static final String PREFS_MIGRATE_HISTORY_DONE = "history_done";
    // Number of history entries already migrated.
    private static final String PREFS_MIGRATE_HISTORY_COUNT = "history_count";
    private static final String PREFS_MIGRATE_SYNC_DONE = "sync_done";

    /*
       These queries are derived from the low-level Places schema
       https://developer.mozilla.org/en/The_Places_database
    */
    private final String kRootQuery =
        "SELECT root_name, folder_id FROM moz_bookmarks_roots";
    private final String kRootName     = "root_name";
    private final String kRootFolderId = "folder_id";

    private final String kBookmarkQuery =
        "SELECT places.url             AS p_url,"         +
        "       bookmark.guid          AS b_guid,"        +
        "       bookmark.id            AS b_id,"          +
        "       bookmark.title         AS b_title,"       +
        "       bookmark.type          AS b_type,"        +
        "       bookmark.parent        AS b_parent,"      +
        "       bookmark.dateAdded     AS b_added,"       +
        "       bookmark.lastModified  AS b_modified,"    +
        "       bookmark.position      AS b_position,"    +
        "       favicon.data           AS f_data,"        +
        "       favicon.mime_type      AS f_mime_type,"   +
        "       favicon.url            AS f_url,"         +
        "       favicon.guid           AS f_guid "        +
        "FROM ((moz_bookmarks AS bookmark "               +
        "       LEFT OUTER JOIN moz_places AS places "    +
        "       ON places.id = bookmark.fk) "             +
        "       LEFT OUTER JOIN moz_favicons AS favicon " +
        "       ON places.favicon_id = favicon.id) "      +
        // Bookmark folders don't have a places entry.
        "WHERE (places.hidden IS NULL "                   +
        "       OR places.hidden <> 1) "                  +
        // This gives us a better chance of adding a folder before
        // adding its contents and hence avoiding extra iterations below.
        "ORDER BY bookmark.id";

    // Result column of relevant data
    private final String kBookmarkUrl      = "p_url";
    private final String kBookmarkTitle    = "b_title";
    private final String kBookmarkGuid     = "b_guid";
    private final String kBookmarkId       = "b_id";
    private final String kBookmarkType     = "b_type";
    private final String kBookmarkParent   = "b_parent";
    private final String kBookmarkAdded    = "b_added";
    private final String kBookmarkModified = "b_modified";
    private final String kBookmarkPosition = "b_position";
    private final String kFaviconData      = "f_data";
    private final String kFaviconMime      = "f_mime_type";
    private final String kFaviconUrl       = "f_url";
    private final String kFaviconGuid      = "f_guid";

    // Helper constants
    private static final int kPlacesTypeBookmark = 1;
    private static final int kPlacesTypeFolder   = 2;

    /*
      For statistics keeping.
    */
    private final String kHistoryCountQuery =
        "SELECT COUNT(*) FROM moz_historyvisits";

    /*
      The sort criterion here corresponds to the one used for the
      Awesomebar results. It's a simplification of Frecency.
      We must divide date by 1000 due to the micro (Places)
      vs milli (Android) distiction.
    */
    private final String kHistoryQuery =
        "SELECT places.url              AS p_url, "       +
        "       places.title            AS p_title, "     +
        "       MAX(history.visit_date) AS h_date, "      +
        "       COUNT(*) AS h_visits, "                   +
        // see BrowserDB.filterAllSites for this formula
        "       MAX(1, 100 * 225 / (" +
                  "((MAX(history.visit_date)/1000 - ?) / 86400000) * " +
                  "((MAX(history.visit_date)/1000 - ?) / 86400000) + 225)) AS a_recent, " +
        "       favicon.data            AS f_data, "      +
        "       favicon.mime_type       AS f_mime_type, " +
        "       places.guid             AS p_guid, "      +
        "       favicon.url             AS f_url, "       +
        "       favicon.guid            AS f_guid "       +
        "FROM (moz_historyvisits AS history "             +
        "      JOIN moz_places AS places "                +
        "      ON places.id = history.place_id "          +
        // Add favicon data if a favicon is present for this URL.
        "      LEFT OUTER JOIN moz_favicons AS favicon "  +
        "      ON places.favicon_id = favicon.id) "       +
        "WHERE places.hidden <> 1 "                       +
        "GROUP BY p_url "                                 +
        "ORDER BY h_visits * a_recent "                   +
        "DESC LIMIT ? OFFSET ?";

    private final String kHistoryUrl    = "p_url";
    private final String kHistoryTitle  = "p_title";
    private final String kHistoryGuid   = "p_guid";
    private final String kHistoryDate   = "h_date";
    private final String kHistoryVisits = "h_visits";

    /*
      Sync settings to get from prefs.js.
    */
    private final String[] kSyncSettingsList = new String[] {
        "services.sync.account",
        "services.sync.client.name",
        "services.sync.client.GUID",
        "services.sync.serverURL",
        "services.sync.clusterURL"
    };

    /*
      Sync settings to get from password manager.
    */
    private final String kSyncHostName = "chrome://weave";
    private final String[] kSyncRealmList = new String[] {
        "Mozilla Services Password",
        "Mozilla Services Encryption Passphrase"
    };


    public ProfileMigrator(Context context, File profileDir) {
        mProfileDir = profileDir;
        mContext = context;
        mCr = mContext.getContentResolver();
    }

    public void launchPlaces() {
        boolean timeThisRun = false;
        Telemetry.Timer timer = null;
        // First run, time things
        if (!hasMigrationRun()) {
            timeThisRun = true;
            timer = new Telemetry.Timer("BROWSERPROVIDER_XUL_IMPORT_TIME");
        }
        launchPlaces(DEFAULT_HISTORY_MIGRATE_COUNT);
        if (timeThisRun)
            timer.stop();
    }

    public void launchPlaces(int maxEntries) {
        // Places migration is heavy on the phone, allow it to block
        // other processing.
        new PlacesRunnable(maxEntries).run();
    }

    public void launchSyncPrefs() {
        // Sync settings will post a runnable, no need for a seperate thread.
        new SyncTask().run();
    }

    public boolean areBookmarksMigrated() {
        return getPreferences().getBoolean(PREFS_MIGRATE_BOOKMARKS_DONE, false);
    }

    public boolean isHistoryMigrated() {
        return getPreferences().getBoolean(PREFS_MIGRATE_HISTORY_DONE, false);
    }

    // Have Sync settings been transferred?
    public boolean hasSyncMigrated() {
        return getPreferences().getBoolean(PREFS_MIGRATE_SYNC_DONE, false);
    }

    // Has migration run before?
    protected boolean hasMigrationRun() {
        return areBookmarksMigrated() && (getMigratedHistoryEntries() > 0);
    }

    // Has migration entirely finished?
    protected boolean hasMigrationFinished() {
        return areBookmarksMigrated() && isHistoryMigrated();
    }

    protected SharedPreferences getPreferences() {
        return mContext.getSharedPreferences(PREFS_NAME, 0);
    }

    protected int getMigratedHistoryEntries() {
        return getPreferences().getInt(PREFS_MIGRATE_HISTORY_COUNT, 0);
    }

    protected void setMigratedHistoryEntries(int count) {
        SharedPreferences.Editor editor = getPreferences().edit();
        editor.putInt(PREFS_MIGRATE_HISTORY_COUNT, count);
        editor.commit();
    }

    protected void setMigratedHistory() {
        SharedPreferences.Editor editor = getPreferences().edit();
        editor.putBoolean(PREFS_MIGRATE_HISTORY_DONE, true);
        editor.commit();
    }

    protected void setMigratedBookmarks() {
        SharedPreferences.Editor editor = getPreferences().edit();
        editor.putBoolean(PREFS_MIGRATE_BOOKMARKS_DONE, true);
        editor.commit();
    }

    protected void setMigratedSync() {
        SharedPreferences.Editor editor = getPreferences().edit();
        editor.putBoolean(PREFS_MIGRATE_SYNC_DONE, true);
        editor.commit();
    }

    private class SyncTask implements Runnable, GeckoEventListener {
        private List<String> mSyncSettingsList;
        private Map<String, String> mSyncSettingsMap;

        // Initialize preferences by sending the "Preferences:Get" command to Gecko
        protected void requestValues() {
            mSyncSettingsList = Arrays.asList(kSyncSettingsList);
            mSyncSettingsMap = new HashMap<String, String>();
            JSONArray jsonPrefs = new JSONArray(mSyncSettingsList);
            Log.d(LOGTAG, "Sending: " + jsonPrefs.toString());
            GeckoEvent event =
                GeckoEvent.createBroadcastEvent("Preferences:Get",
                                                jsonPrefs.toString());
            GeckoAppShell.sendEventToGecko(event);
        }

        // Receive settings reply from Gecko, do the rest of the setup
        public void handleMessage(String event, JSONObject message) {
            Log.d(LOGTAG, "Received event: " + event);
            try {
                if (event.equals("Preferences:Data")) {
                    // Receive most settings from Gecko's service.
                    // This includes personal info, so don't log.
                    // Log.d(LOGTAG, "Message: " + message.toString());
                    JSONArray jsonPrefs = message.getJSONArray("preferences");
                    parsePrefs(jsonPrefs);
                    GeckoAppShell.unregisterGeckoEventListener("Preferences:Data",
                                                               (GeckoEventListener)this);

                    // Now call the password provider to fill in the rest.
                    for (String location: kSyncRealmList) {
                        Log.d(LOGTAG, "Checking: " + location);
                        String passwd = getPassword(location);
                        if (!TextUtils.isEmpty(passwd)) {
                            Log.d(LOGTAG, "Got password");
                            mSyncSettingsMap.put(location, passwd);
                        } else {
                            Log.d(LOGTAG, "No password found");
                            mSyncSettingsMap.put(location, null);
                        }
                    }

                    // Call Sync and transfer settings.
                    configureSync();
                }
            } catch (Exception e) {
                Log.e(LOGTAG, "Exception handling message \"" + event + "\":", e);
            }
        }

        protected String getPassword(String realm) {
            Cursor cursor = null;
            String result = null;
            try {
                cursor = mCr.query(Passwords.CONTENT_URI,
                                   null,
                                   Passwords.HOSTNAME + " = ? AND "
                                   + Passwords.HTTP_REALM + " = ?",
                                   new String[] { kSyncHostName, realm },
                                   null);

                if (cursor != null) {
                    final int userCol =
                        cursor.getColumnIndexOrThrow(Passwords.ENCRYPTED_USERNAME);
                    final int passCol =
                        cursor.getColumnIndexOrThrow(Passwords.ENCRYPTED_PASSWORD);

                    if (cursor.moveToFirst()) {
                        String user = cursor.getString(userCol);
                        String pass = cursor.getString(passCol);
                        result = pass;
                    } else {
                        Log.i(LOGTAG, "No password found for realm = " + realm);
                    }
                }
            } finally {
                if (cursor != null)
                    cursor.close();
            }

            return result;
        }

        protected void parsePrefs(JSONArray jsonPrefs) {
            try {
                final int length = jsonPrefs.length();
                for (int i = 0; i < length; i++) {
                    JSONObject jPref = jsonPrefs.getJSONObject(i);
                    final String prefName = jPref.getString("name");
                    final String prefType = jPref.getString("type");
                    if ("bool".equals(prefType)) {
                        final boolean value = jPref.getBoolean("value");
                        mSyncSettingsMap.put(prefName, value ? "1" : "0");
                    } else {
                        final String value = jPref.getString("value");
                        if (!TextUtils.isEmpty(value)) {
                            mSyncSettingsMap.put(prefName, value);
                        } else {
                            Log.w(LOGTAG, "Could not recover setting for = " + prefName);
                            mSyncSettingsMap.put(prefName, null);
                        }
                    }
                }
            } catch (JSONException e) {
                Log.e(LOGTAG, "Exception handling preferences answer: "
                      + e.getMessage());
            }
        }

        protected void configureSync() {
            final String userName = mSyncSettingsMap.get("services.sync.account");
            final String syncKey = mSyncSettingsMap.get("Mozilla Services Password");
            final String syncPass = mSyncSettingsMap.get("Mozilla Services Encryption Passphrase");
            final String serverURL = mSyncSettingsMap.get("services.sync.serverURL");
            final String clusterURL = mSyncSettingsMap.get("services.sync.clusterURL");
            final String clientName = mSyncSettingsMap.get("services.sync.client.name");
            final String clientGuid = mSyncSettingsMap.get("services.sync.client.GUID");

            if (userName == null || syncKey == null || syncPass == null) {
                // This isn't going to work. Give up.
                Log.e(LOGTAG, "Profile has incomplete Sync config. Not migrating.");
                setMigratedSync();
                return;
            }

            final SyncAccountParameters params =
                new SyncAccountParameters(mContext, null,
                                          userName, syncKey,
                                          syncPass, serverURL, clusterURL,
                                          clientName, clientGuid);

            new SyncAccounts.CreateSyncAccountTask() {
                @Override
                protected void onPostExecute(Account account) {
                    if (account == null) {
                        Log.e(LOGTAG, "Failed to migrate Sync account.");
                    } else {
                        Log.i(LOGTAG, "Migrating Sync account succeeded.");
                    }
                    setMigratedSync();
                }
            }.execute(params);
        }

        protected void registerAndRequest() {
            GeckoAppShell.getHandler().post(new Runnable() {
                public void run() {
                    GeckoAppShell.registerGeckoEventListener("Preferences:Data",
                                                             SyncTask.this);
                    requestValues();
                }
            });
        }

        @Override
        public void run() {
            // Run only if no Sync accounts exist.
            new SyncAccounts.AccountsExistTask() {
                @Override
                protected void onPostExecute(Boolean result) {
                    if (result.booleanValue()) {
                        Log.i(LOGTAG, "Sync account already configured, skipping.");
                        setMigratedSync();
                    } else {
                        // No account configured, fire up.
                        registerAndRequest();
                    }
                }
            }.execute(mContext);
        }
    }

    private class MiscTask implements Runnable {
        protected void cleanupXULLibCache() {
            File cacheFile = GeckoAppShell.getCacheDir(mContext);
            File[] files = cacheFile.listFiles();
            if (files != null) {
                Iterator cacheFiles = Arrays.asList(files).iterator();
                while (cacheFiles.hasNext()) {
                    File libFile = (File)cacheFiles.next();
                    if (libFile.getName().endsWith(".so")) {
                        libFile.delete();
                    }
                }
            }
        }

        @Override
        public void run() {
            // XXX: Land dependent bugs (732069) first
            // cleanupXULLibCache();
        }
    }

    private class PlacesRunnable implements Runnable {
        private Map<Long, Long> mRerootMap;
        private ArrayList<ContentProviderOperation> mOperations;
        private int mMaxEntries;

        public PlacesRunnable(int limit) {
            mMaxEntries = limit;
        }

        protected Uri getBookmarksUri() {
            Uri.Builder uriBuilder = Bookmarks.CONTENT_URI.buildUpon()
                .appendQueryParameter(BrowserContract.PARAM_SHOW_DELETED, "1");
            return uriBuilder.build();
        }

        protected Uri getHistoryUri() {
            Uri.Builder uriBuilder = History.CONTENT_URI.buildUpon()
                .appendQueryParameter(BrowserContract.PARAM_SHOW_DELETED, "1");
            return uriBuilder.build();

        }

        protected Uri getImagesUri() {
            Uri.Builder uriBuilder = Images.CONTENT_URI.buildUpon()
                .appendQueryParameter(BrowserContract.PARAM_SHOW_DELETED, "1");
            return uriBuilder.build();
        }

        private long getFolderId(String guid) {
            Cursor c = null;

            try {
                c = mCr.query(getBookmarksUri(),
                              new String[] { Bookmarks._ID },
                              Bookmarks.GUID + " = ?",
                              new String [] { guid },
                              null);
                if (c.moveToFirst())
                    return c.getLong(c.getColumnIndexOrThrow(Bookmarks._ID));
            } finally {
                if (c != null)
                    c.close();
            }
            // Default fallback
            return Bookmarks.FIXED_ROOT_ID;
        }

        // We want to know the id of special root folders in the places DB,
        // and replace them by the corresponding root id in the Android DB.
        protected void calculateReroot(SQLiteBridge db) {
            mRerootMap = new HashMap<Long, Long>();

            try {
                Cursor cursor = db.rawQuery(kRootQuery, null);
                final int rootCol = cursor.getColumnIndex(kRootName);
                final int folderCol = cursor.getColumnIndex(kRootFolderId);

                cursor.moveToFirst();
                while (!cursor.isAfterLast()) {
                    String name = cursor.getString(rootCol);
                    long placesFolderId = cursor.getLong(folderCol);
                    mRerootMap.put(placesFolderId, getFolderId(name));
                    Log.v(LOGTAG, "Name: " + name + ", pid=" + placesFolderId
                          + ", nid=" + mRerootMap.get(placesFolderId));
                    cursor.moveToNext();
                }
                cursor.close();
            } catch (SQLiteBridgeException e) {
                Log.e(LOGTAG, "Failed to get bookmark roots: ", e);
                return;
            }
        }

        protected void updateBrowserHistory(String url, String title,
                                            long date, int visits) {
            Cursor cursor = null;

            try {
                final String[] projection = new String[] {
                    History._ID,
                    History.VISITS,
                    History.DATE_LAST_VISITED
                };

                cursor = mCr.query(getHistoryUri(),
                                   projection,
                                   History.URL + " = ?",
                                   new String[] { url },
                                   null);

                ContentValues values = new ContentValues();
                ContentProviderOperation.Builder builder = null;
                // Restore deleted record if possible
                values.put(History.IS_DELETED, 0);

                if (cursor.moveToFirst()) {
                    int visitsCol = cursor.getColumnIndexOrThrow(History.VISITS);
                    int dateCol = cursor.getColumnIndexOrThrow(History.DATE_LAST_VISITED);
                    int oldVisits = cursor.getInt(visitsCol);
                    long oldDate = cursor.getLong(dateCol);

                    values.put(History.VISITS, oldVisits + visits);
                    if (title != null) {
                        values.put(History.TITLE, title);
                    }
                    // Only update last visited if newer.
                    if (date > oldDate) {
                        values.put(History.DATE_LAST_VISITED, date);
                    }

                    int idCol = cursor.getColumnIndexOrThrow(History._ID);
                    // We use default profile anyway
                    Uri historyUri = ContentUris.withAppendedId(getHistoryUri(),
                                                                cursor.getLong(idCol));

                    // Update
                    builder = ContentProviderOperation.newUpdate(historyUri);
                    // URL should be unique and we should hit it
                    builder.withExpectedCount(1);
                    builder.withValues(values);
                } else {
                    values.put(History.URL, url);
                    values.put(History.VISITS, visits);
                    if (title != null) {
                        values.put(History.TITLE, title);
                    } else {
                        values.put(History.TITLE, url);
                    }
                    values.put(History.DATE_LAST_VISITED, date);

                    // Insert
                    builder = ContentProviderOperation.newInsert(getHistoryUri());
                    builder.withValues(values);
                }

                // Queue the operation
                mOperations.add(builder.build());
            } finally {
                if (cursor != null)
                    cursor.close();
            }
        }

        protected BitmapDrawable decodeImageData(byte[] data) {
            InputStream byteStream = new ByteArrayInputStream(data);
            BitmapDrawable image =
                (BitmapDrawable)Drawable.createFromStream(byteStream, "src");
            return image;
        }

        protected void addFavicon(String url, String faviconUrl, String faviconGuid,
                                  String mime, byte[] data) {
            // Some GIFs can cause us to lock up completely
            // without exceptions or anything. Not cool.
            if (mime == null || mime.compareTo("image/gif") == 0) {
                return;
            }
            BitmapDrawable image = null;
            // Decode non-PNG images.
            if (mime.compareTo("image/png") != 0) {
                image = decodeImageData(data);
                // Can't decode, give up.
                if (image == null) {
                    Log.i(LOGTAG, "Cannot decode image type " + mime
                          + " for URL=" + url);
                }
            }
            try {
                ContentValues values = new ContentValues();

                // Recompress decoded images to PNG.
                if (image != null) {
                    Bitmap bitmap = image.getBitmap();
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                    values.put(Images.FAVICON, stream.toByteArray());
                } else {
                    // PNG images can be passed directly. Well, aside
                    // from having to convert them into a byte[].
                    values.put(Images.FAVICON, data);
                }

                values.put(Images.URL, url);
                values.put(Images.FAVICON_URL, faviconUrl);
                // Restore deleted record if possible
                values.put(Images.IS_DELETED, 0);
                values.put(Images.GUID, faviconGuid);

                Cursor cursor = null;
                ContentProviderOperation.Builder builder = null;
                try {
                    cursor = mCr.query(getImagesUri(),
                                       null,
                                       Images.URL + " = ?",
                                       new String[] { url },
                                       null);

                    if (cursor != null && cursor.moveToFirst()) {
                        // Update
                        builder = ContentProviderOperation.newUpdate(getImagesUri());
                        // URL should be unique and we should hit it
                        builder.withExpectedCount(1);
                        builder.withValues(values);
                        builder.withSelection(Images.URL + " = ?",
                                              new String[] { url });
                    } else {
                        // Insert
                        builder = ContentProviderOperation.newInsert(getImagesUri());
                        builder.withValues(values);
                    }
                } finally {
                    if (cursor != null)
                        cursor.close();
                }

                // Queue the operation
                mOperations.add(builder.build());
            } catch (SQLException e) {
                Log.i(LOGTAG, "Migrating favicon failed: " + mime + " URL: " + url
                      + " error:" + e.getMessage());
            }
        }

        protected void doMigrateHistoryBatch(SQLiteBridge db,
                                             int maxEntries, int currentEntries) {
            final ArrayList<String> placesHistory = new ArrayList<String>();
            mOperations = new ArrayList<ContentProviderOperation>();
            int queryResultEntries = 0;

            try {
                Cursor cursor = db.rawQuery(kHistoryCountQuery, null);
                cursor.moveToFirst();
                int historyCount = cursor.getInt(0);
                Telemetry.HistogramAdd("BROWSERPROVIDER_XUL_IMPORT_HISTORY",
                                       historyCount);

                final String currentTime = Long.toString(System.currentTimeMillis());
                final String[] queryParams = new String[] {
                    /* current time */
                    currentTime,
                    currentTime,
                    Integer.toString(maxEntries),
                    Integer.toString(currentEntries)
                };
                cursor = db.rawQuery(kHistoryQuery, queryParams);
                queryResultEntries = cursor.getCount();

                final int urlCol = cursor.getColumnIndex(kHistoryUrl);
                final int titleCol = cursor.getColumnIndex(kHistoryTitle);
                final int dateCol = cursor.getColumnIndex(kHistoryDate);
                final int visitsCol = cursor.getColumnIndex(kHistoryVisits);
                final int faviconMimeCol = cursor.getColumnIndex(kFaviconMime);
                final int faviconDataCol = cursor.getColumnIndex(kFaviconData);
                final int faviconUrlCol = cursor.getColumnIndex(kFaviconUrl);
                final int faviconGuidCol = cursor.getColumnIndex(kFaviconGuid);

                cursor.moveToFirst();
                while (!cursor.isAfterLast()) {
                    String url = cursor.getString(urlCol);
                    String title = cursor.getString(titleCol);
                    long date = cursor.getLong(dateCol) / (long)1000;
                    int visits = cursor.getInt(visitsCol);
                    byte[] faviconDataBuff = cursor.getBlob(faviconDataCol);
                    String faviconMime = cursor.getString(faviconMimeCol);
                    String faviconUrl = cursor.getString(faviconUrlCol);
                    String faviconGuid = cursor.getString(faviconGuidCol);

                    try {
                        placesHistory.add(url);
                        addFavicon(url, faviconUrl, faviconGuid,
                                   faviconMime, faviconDataBuff);
                        updateBrowserHistory(url, title, date, visits);
                    } catch (Exception e) {
                        Log.e(LOGTAG, "Error adding history entry: ", e);
                    }
                    cursor.moveToNext();
                }
                cursor.close();
            } catch (SQLiteBridgeException e) {
                Log.e(LOGTAG, "Failed to get history: ", e);
                return;
            }

            flushBatchOperations();

            int totalEntries = currentEntries + queryResultEntries;
            setMigratedHistoryEntries(totalEntries);

            // Reached the end of the history list? Then stop.
            // We're at the end if we got less results than requested.
            if (queryResultEntries < mMaxEntries) {
                setMigratedHistory();
            }

            // GlobalHistory access communicates with Gecko
            // and must run on its thread.
            GeckoAppShell.getHandler().post(new Runnable() {
                    public void run() {
                        for (String url : placesHistory) {
                            GlobalHistory.getInstance().addToGeckoOnly(url);
                        }
                    }
             });
        }

        protected void migrateHistory(SQLiteBridge db) {
            for (int i = 0; i < mMaxEntries; i += HISTORY_MAX_BATCH) {
                int currentEntries = getMigratedHistoryEntries();
                int fetchEntries = Math.min(mMaxEntries, HISTORY_MAX_BATCH);

                Log.i(LOGTAG, "Processed " + currentEntries + " history entries");
                Log.i(LOGTAG, "Fetching " + fetchEntries + " more history entries");

                doMigrateHistoryBatch(db, fetchEntries, currentEntries);
            }
        }

        protected void addBookmark(String url, String title, String guid,
                                   long parent, long added,
                                   long modified, long position,
                                   boolean folder) {
            ContentValues values = new ContentValues();
            if (title == null && url != null) {
                title = url;
            }
            if (title != null) {
                values.put(Bookmarks.TITLE, title);
            }
            if (url != null) {
                values.put(Bookmarks.URL, url);
            }
            if (guid != null) {
                values.put(SyncColumns.GUID, guid);
            }
            values.put(SyncColumns.DATE_CREATED, added);
            values.put(SyncColumns.DATE_MODIFIED, modified);
            values.put(Bookmarks.POSITION, position);
            // Restore deleted record if possible
            values.put(Bookmarks.IS_DELETED, 0);
            if (mRerootMap.containsKey(parent)) {
                parent = mRerootMap.get(parent);
            }
            values.put(Bookmarks.PARENT, parent);
            values.put(Bookmarks.TYPE, (folder ? Bookmarks.TYPE_FOLDER : Bookmarks.TYPE_BOOKMARK));

            Cursor cursor = null;
            ContentProviderOperation.Builder builder = null;

            if (url != null) {
                try {
                    final String[] projection = new String[] {
                        Bookmarks._ID,
                        Bookmarks.URL
                    };

                    // Check if the boomark exists
                    cursor = mCr.query(getBookmarksUri(),
                                       projection,
                                       Bookmarks.URL + " = ?",
                                       new String[] { url },
                                       null);

                    if (cursor.moveToFirst()) {
                        int idCol = cursor.getColumnIndexOrThrow(Bookmarks._ID);
                        // We use default profile anyway
                        Uri bookmarkUri = ContentUris.withAppendedId(getBookmarksUri(),
                                                                     cursor.getLong(idCol));
                        // Update
                        builder = ContentProviderOperation.newUpdate(bookmarkUri);
                        // URL should be unique and we should hit it
                        builder.withExpectedCount(1);
                        builder.withValues(values);
                    } else {
                        // Insert
                        builder = ContentProviderOperation.newInsert(getBookmarksUri());
                        builder.withValues(values);
                    }
                } finally {
                    if (cursor != null)
                        cursor.close();
                }
            } else {
                // Insert
                builder = ContentProviderOperation.newInsert(getBookmarksUri());
                builder.withValues(values);
            }

            // Queue the operation
            mOperations.add(builder.build());
        }

        protected void migrateBookmarks(SQLiteBridge db) {
            mOperations = new ArrayList<ContentProviderOperation>();

            try {
                Log.i(LOGTAG, "Fetching bookmarks from places");

                Cursor cursor = db.rawQuery(kBookmarkQuery, null);
                final int urlCol = cursor.getColumnIndex(kBookmarkUrl);
                final int titleCol = cursor.getColumnIndex(kBookmarkTitle);
                final int guidCol = cursor.getColumnIndex(kBookmarkGuid);
                final int idCol = cursor.getColumnIndex(kBookmarkId);
                final int typeCol = cursor.getColumnIndex(kBookmarkType);
                final int parentCol = cursor.getColumnIndex(kBookmarkParent);
                final int addedCol = cursor.getColumnIndex(kBookmarkAdded);
                final int modifiedCol = cursor.getColumnIndex(kBookmarkModified);
                final int positionCol = cursor.getColumnIndex(kBookmarkPosition);
                final int faviconMimeCol = cursor.getColumnIndex(kFaviconMime);
                final int faviconDataCol = cursor.getColumnIndex(kFaviconData);
                final int faviconUrlCol = cursor.getColumnIndex(kFaviconUrl);
                final int faviconGuidCol = cursor.getColumnIndex(kFaviconGuid);

                // Keep statistics
                int bookmarkCount = cursor.getCount();
                Telemetry.HistogramAdd("BROWSERPROVIDER_XUL_IMPORT_BOOKMARKS",
                                       bookmarkCount);

                // The keys are places IDs.
                Set<Long> openFolders = new HashSet<Long>();
                Set<Long> knownFolders = new HashSet<Long>(mRerootMap.keySet());

                // We iterate over all bookmarks, and add all bookmarks that
                // have their parent folders present. If there are bookmarks
                // that we can't add, we remember what these are and try again
                // on the next iteration. The number of iterations scales
                // according to the depth of the folders.
                // No need to import root folders for which we have a remapping.
                Set<Long> processedBookmarks = new HashSet<Long>(mRerootMap.keySet());

                int iterations = 0;
                do {
                    // Reset the set of missing folders that block us from
                    // adding entries.
                    openFolders.clear();

                    int added = 0;
                    int skipped = 0;

                    cursor.moveToFirst();
                    while (!cursor.isAfterLast()) {
                        long id = cursor.getLong(idCol);

                        // Already processed? if so just skip
                        if (processedBookmarks.contains(id)) {
                            cursor.moveToNext();
                            continue;
                        }

                        int type = cursor.getInt(typeCol);
                        long parent = cursor.getLong(parentCol);

                        // Places has an explicit root folder, id=1 parent=0.
                        // Skip that.
                        if (id == 1 && parent == 0 && type == kPlacesTypeFolder) {
                            cursor.moveToNext();
                            continue;
                        }

                        String url = cursor.getString(urlCol);
                        String title = cursor.getString(titleCol);
                        String guid = cursor.getString(guidCol);
                        long dateadded =
                            cursor.getLong(addedCol) / (long)1000;
                        long datemodified =
                            cursor.getLong(modifiedCol) / (long)1000;
                        long position = cursor.getLong(positionCol);
                        byte[] faviconDataBuff = cursor.getBlob(faviconDataCol);
                        String faviconMime = cursor.getString(faviconMimeCol);
                        String faviconUrl = cursor.getString(faviconUrlCol);
                        String faviconGuid = cursor.getString(faviconGuidCol);

                        // Is the parent for this bookmark already added?
                        // If so, we can add the bookmark itself.
                        if (knownFolders.contains(parent)) {
                            try {
                                boolean isFolder = (type == kPlacesTypeFolder);
                                addBookmark(url, title, guid, parent,
                                            dateadded, datemodified,
                                            position, isFolder);
                                addFavicon(url, faviconUrl, faviconGuid,
                                           faviconMime, faviconDataBuff);
                                if (isFolder) {
                                    // We need to know the ID of the folder
                                    // we just inserted. It's possible to
                                    // make future database ops refer to the
                                    // result of this operation, but that makes
                                    // our algorithm to track dependencies too
                                    // complicated. Just flush and be done with it.
                                    flushBatchOperations();
                                    long newFolderId = getFolderId(guid);
                                    // Remap the folder IDs for parents.
                                    mRerootMap.put(id, newFolderId);
                                    knownFolders.add(id);
                                    Log.d(LOGTAG, "Added folder: " + id);
                                }
                                processedBookmarks.add(id);
                            } catch (Exception e) {
                                Log.e(LOGTAG, "Error adding bookmark: ", e);
                            }
                            added++;
                        } else {
                            // We have to postpone until parent is processed;
                            openFolders.add(parent);
                            skipped++;
                        }
                        cursor.moveToNext();
                    }

                    // Now check if any of the new folders we added was a folder
                    // that we were blocked on, by intersecting openFolders and
                    // knownFolders. If this is empty, we're done because the next
                    // iteration can't make progress.
                    boolean changed = openFolders.retainAll(knownFolders);

                    // If there are no folders that we can add next iteration,
                    // but there were still folders before the intersection,
                    // those folders are orphans. Report this situation here.
                    if (openFolders.isEmpty() && changed) {
                        Log.w(LOGTAG, "Orphaned bookmarks found, not imported");
                    }
                    iterations++;
                    Log.i(LOGTAG, "Iteration = " + iterations + ", added " + added +
                          " bookmark(s), skipped " + skipped + " bookmark(s)");
                } while (!openFolders.isEmpty());

                cursor.close();
            } catch (SQLiteBridgeException e) {
                Log.e(LOGTAG, "Failed to get bookmarks: ", e);
                return;
            }

            flushBatchOperations();
        }

        protected void flushBatchOperations() {
            Log.i(LOGTAG, "Flushing " + mOperations.size() + " DB operations");
            try {
                // We don't really care for the results, this is best-effort.
                mCr.applyBatch(BrowserContract.AUTHORITY, mOperations);
            } catch (RemoteException e) {
                Log.e(LOGTAG, "Remote exception while updating db: ", e);
            } catch (OperationApplicationException e) {
                // Bug 716729 means this happens even in normal circumstances
                Log.i(LOGTAG, "Error while applying database updates: ", e);
            }
            mOperations.clear();
        }

        protected void migratePlaces(File aFile) {
            // Typical case: nothing to do, we're done already.
            if (hasMigrationFinished()) {
                Log.i(LOGTAG, "Nothing to migrate, early exit.");
                return;
            }

            String dbPath = aFile.getPath() + "/places.sqlite";
            String dbPathWal = aFile.getPath() + "/places.sqlite-wal";
            String dbPathShm = aFile.getPath() + "/places.sqlite-shm";
            Log.i(LOGTAG, "Opening path: " + dbPath);

            File dbFile = new File(dbPath);
            if (!dbFile.exists()) {
                Log.i(LOGTAG, "No database");
                // Nothing to do, so mark as done.
                setMigratedBookmarks();
                setMigratedHistory();
                return;
            }
            File dbFileWal = new File(dbPathWal);
            File dbFileShm = new File(dbPathShm);

            SQLiteBridge db = null;
            GeckoAppShell.loadSQLiteLibs(mContext, mContext.getPackageResourcePath());
            try {
                db = new SQLiteBridge(dbPath);
                calculateReroot(db);

                if (!areBookmarksMigrated()) {
                    migrateBookmarks(db);
                    setMigratedBookmarks();
                } else {
                    Log.i(LOGTAG, "Bookmarks already migrated. Skipping...");
                }

                if (!isHistoryMigrated()) {
                    migrateHistory(db);
                } else {
                    Log.i(LOGTAG, "History already migrated. Skipping...");
                }

                db.close();

                // Clean up if we finished this run. Bookmarks are always
                // migrated if we get here.
                if (isHistoryMigrated()) {
                    Log.i(LOGTAG, "Profile Migration has processed all entries. "
                          +" Purging old DB.");
                    dbFile.delete();
                    dbFileWal.delete();
                    dbFileShm.delete();
                }

                Log.i(LOGTAG, "Profile Migration run finished");
            } catch (SQLiteBridgeException e) {
                if (db != null) {
                    db.close();
                }
                Log.e(LOGTAG, "Error on places database:", e);
                return;
            }
        }

        @Override
        public void run() {
            migratePlaces(mProfileDir);
        }
    }
}
