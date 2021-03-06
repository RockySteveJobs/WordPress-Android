package org.wordpress.android.ui.prefs.notifications;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.widget.SearchView;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.android.volley.VolleyError;
import com.wordpress.rest.RestRequest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.analytics.AnalyticsTracker;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.store.AccountStore;
import org.wordpress.android.fluxc.store.SiteStore;
import org.wordpress.android.models.NotificationsSettings;
import org.wordpress.android.models.NotificationsSettings.Channel;
import org.wordpress.android.models.NotificationsSettings.Type;
import org.wordpress.android.ui.notifications.NotificationEvents;
import org.wordpress.android.ui.notifications.utils.NotificationsUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.SiteUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.ToastUtils.Duration;
import org.wordpress.android.util.WPActivityUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import de.greenrobot.event.EventBus;

public class NotificationsSettingsFragment extends PreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String KEY_SEARCH_QUERY = "search_query";
    private static final int SITE_SEARCH_VISIBILITY_COUNT = 15;
    // The number of notification types we support (e.g. timeline, email, mobile)
    private static final int TYPE_COUNT = 3;
    private static final int NO_MAXIMUM = -1;
    private static final int MAX_SITES_TO_SHOW_ON_FIRST_SCREEN = 3;

    private NotificationsSettings mNotificationsSettings;
    private SearchView mSearchView;
    private MenuItem mSearchMenuItem;
    private boolean mSearchMenuItemCollapsed = true;

    private String mDeviceId;
    private String mRestoredQuery;
    private boolean mNotificationsEnabled;
    private int mSiteCount;

    private final List<PreferenceCategory> mTypePreferenceCategories = new ArrayList<>();
    private PreferenceCategory mBlogsCategory;


    @Inject AccountStore mAccountStore;
    @Inject SiteStore mSiteStore;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ((WordPress) getActivity().getApplication()).component().inject(this);

        addPreferencesFromResource(R.xml.notifications_settings);
        setHasOptionsMenu(true);

        // Bump Analytics
        if (savedInstanceState == null) {
            AnalyticsTracker.track(AnalyticsTracker.Stat.NOTIFICATION_SETTINGS_LIST_OPENED);
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getActivity());
        mDeviceId = settings.getString(NotificationsUtils.WPCOM_PUSH_DEVICE_SERVER_ID, "");

        if (hasNotificationsSettings()) {
            loadNotificationsAndUpdateUI(true);
        }

        if (savedInstanceState != null && savedInstanceState.containsKey(KEY_SEARCH_QUERY)) {
            mRestoredQuery = savedInstanceState.getString(KEY_SEARCH_QUERY);
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();

        mNotificationsEnabled = NotificationsUtils.isNotificationsEnabled(getActivity());
        refreshSettings();
    }

    @Override
    public void onStop() {
        super.onStop();

        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.notifications_settings, menu);

        mSearchMenuItem = menu.findItem(R.id.menu_notifications_settings_search);
        mSearchView = (SearchView) MenuItemCompat.getActionView(mSearchMenuItem);
        mSearchView.setQueryHint(getString(R.string.search_sites));
        mBlogsCategory = (PreferenceCategory) findPreference(
                getString(R.string.pref_notification_blogs));

        mSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                configureBlogsSettings(mBlogsCategory, true);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                // we need to perform this check because when the search menu item is collapsed
                // a new queryTExtChange event is triggered with an empty value "", and we only
                // would want to take care of it when the user actively opened/cleared the search term
                configureBlogsSettings(mBlogsCategory, !mSearchMenuItemCollapsed);
                return true;
            }
        });

        MenuItemCompat.setOnActionExpandListener(mSearchMenuItem, new MenuItemCompat.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                mSearchMenuItemCollapsed = false;
                configureBlogsSettings(mBlogsCategory, true);
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                mSearchMenuItemCollapsed = true;
                configureBlogsSettings(mBlogsCategory, false);
                return true;
            }
        });

        updateSearchMenuVisibility();

        // Check for a restored search query (if device was rotated, etc)
        if (!TextUtils.isEmpty(mRestoredQuery)) {
            mSearchMenuItem.expandActionView();
            mSearchView.setQuery(mRestoredQuery, true);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (mSearchView != null && !TextUtils.isEmpty(mSearchView.getQuery())) {
            outState.putString(KEY_SEARCH_QUERY, mSearchView.getQuery().toString());
        }

        super.onSaveInstanceState(outState);
    }

    private void refreshSettings() {
        if (!hasNotificationsSettings()) {
            EventBus.getDefault()
                    .post(new NotificationEvents.NotificationsSettingsStatusChanged(getString(R.string.loading)));
        }

        if (hasNotificationsSettings()) {
            updateUIForNotificationsEnabledState();
        }

        if (!mAccountStore.hasAccessToken()) {
            return;
        }

        NotificationsUtils.getPushNotificationSettings(getActivity(), new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject response) {
                AppLog.d(T.NOTIFS, "Get settings action succeeded");
                if (!isAdded()) {
                    return;
                }

                boolean settingsExisted = hasNotificationsSettings();
                if (!settingsExisted) {
                    EventBus.getDefault().post(new NotificationEvents.NotificationsSettingsStatusChanged(null));
                }

                SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getActivity());
                SharedPreferences.Editor editor = settings.edit();
                editor.putString(NotificationsUtils.WPCOM_PUSH_DEVICE_NOTIFICATION_SETTINGS, response.toString());
                editor.apply();

                loadNotificationsAndUpdateUI(!settingsExisted);
                updateUIForNotificationsEnabledState();
            }
        }, new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                if (!isAdded()) {
                    return;
                }
                AppLog.e(T.NOTIFS, "Get settings action failed", error);

                if (!hasNotificationsSettings()) {
                    EventBus.getDefault().post(new NotificationEvents.NotificationsSettingsStatusChanged(
                            getString(R.string.error_loading_notifications)));
                }
            }
        });
    }

    private void loadNotificationsAndUpdateUI(boolean shouldUpdateUI) {
        JSONObject settingsJson;
        try {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
            settingsJson = new JSONObject(
                    sharedPreferences.getString(NotificationsUtils.WPCOM_PUSH_DEVICE_NOTIFICATION_SETTINGS, "")
            );
        } catch (JSONException e) {
            AppLog.e(T.NOTIFS, "Could not parse notifications settings JSON");
            return;
        }

        if (mNotificationsSettings == null) {
            mNotificationsSettings = new NotificationsSettings(settingsJson);
        } else {
            mNotificationsSettings.updateJson(settingsJson);
        }

        if (shouldUpdateUI) {
            if (mBlogsCategory == null) {
                mBlogsCategory = (PreferenceCategory) findPreference(
                        getString(R.string.pref_notification_blogs));
            }

            configureBlogsSettings(mBlogsCategory, false);
            configureOtherSettings();
            configureWPComSettings();
        }
    }

    private boolean hasNotificationsSettings() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getActivity());

        return sharedPreferences.contains(NotificationsUtils.WPCOM_PUSH_DEVICE_NOTIFICATION_SETTINGS);
    }

    // Updates the UI for preference screens based on if notifications are enabled or not
    private void updateUIForNotificationsEnabledState() {
        if (mTypePreferenceCategories == null || mTypePreferenceCategories.size() == 0) {
            return;
        }

        for (final PreferenceCategory category : mTypePreferenceCategories) {
            if (mNotificationsEnabled && category.getPreferenceCount() > TYPE_COUNT) {
                category.removePreference(category.getPreference(TYPE_COUNT));
            } else if (!mNotificationsEnabled && category.getPreferenceCount() == TYPE_COUNT) {
                Preference disabledMessage = new Preference(getActivity());
                disabledMessage.setSummary(R.string.notifications_disabled);
                disabledMessage.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        Intent intent = new Intent();
                        intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        Uri uri =
                                Uri.fromParts("package", getActivity().getApplicationContext().getPackageName(), null);
                        intent.setData(uri);

                        startActivity(intent);
                        return true;
                    }
                });

                category.addPreference(disabledMessage);
            }

            if (category.getPreferenceCount() >= TYPE_COUNT
                && category.getPreference(TYPE_COUNT - 1) != null) {
                category.getPreference(TYPE_COUNT - 1).setEnabled(mNotificationsEnabled);
            }
        }
    }

    private void configureBlogsSettings(PreferenceCategory blogsCategory, boolean showAll) {
        if (!isAdded()) {
            return;
        }

        List<SiteModel> sites;
        String trimmedQuery = "";
        if (mSearchView != null && !TextUtils.isEmpty(mSearchView.getQuery())) {
            trimmedQuery = mSearchView.getQuery().toString().trim();
            sites = mSiteStore.getSitesAccessedViaWPComRestByNameOrUrlMatching(trimmedQuery);
        } else {
            sites = mSiteStore.getSitesAccessedViaWPComRest();
        }
        mSiteCount = sites.size();

        Context context = getActivity();

        blogsCategory.removeAll();

        int maxSitesToShow = showAll ? NO_MAXIMUM : MAX_SITES_TO_SHOW_ON_FIRST_SCREEN;
        int count = 0;
        for (SiteModel site : sites) {
            if (context == null) {
                return;
            }

            count++;
            if (maxSitesToShow != NO_MAXIMUM && count > maxSitesToShow) {
                break;
            }

            PreferenceScreen prefScreen = getPreferenceManager().createPreferenceScreen(context);
            prefScreen.setTitle(SiteUtils.getSiteNameOrHomeURL(site));
            prefScreen.setSummary(SiteUtils.getHomeURLOrHostName(site));
            addPreferencesForPreferenceScreen(prefScreen, Channel.BLOGS, site.getSiteId());
            blogsCategory.addPreference(prefScreen);
        }

        // Add a message in a preference if there are no matching search results
        if (mSiteCount == 0 && !TextUtils.isEmpty(trimmedQuery)) {
            Preference searchResultsPref = new Preference(context);
            searchResultsPref
                    .setSummary(String.format(getString(R.string.notifications_no_search_results), trimmedQuery));
            blogsCategory.addPreference(searchResultsPref);
        }

        if (mSiteCount > maxSitesToShow && !showAll) {
            // append a "view all" option
            appendViewAllSitesOption(context);
        }

        updateSearchMenuVisibility();
    }

    private void appendViewAllSitesOption(Context context) {
        PreferenceCategory blogsCategory = (PreferenceCategory) findPreference(
                getString(R.string.pref_notification_blogs));

        PreferenceScreen prefScreen = getPreferenceManager().createPreferenceScreen(context);
        prefScreen.setTitle(R.string.notification_settings_item_your_sites_all_your_sites);
        addSitesForViewAllSitesScreen(prefScreen);
        blogsCategory.addPreference(prefScreen);
    }

    private void updateSearchMenuVisibility() {
        // Show the search menu item in the toolbar if we have enough sites
        if (mSearchMenuItem != null) {
            mSearchMenuItem.setVisible(mSiteCount > SITE_SEARCH_VISIBILITY_COUNT);
        }
    }

    private void configureOtherSettings() {
        PreferenceScreen otherBlogsScreen = (PreferenceScreen) findPreference(
                getString(R.string.pref_notification_other_blogs));
        addPreferencesForPreferenceScreen(otherBlogsScreen, Channel.OTHER, 0);
    }

    private void configureWPComSettings() {
        PreferenceCategory otherPreferenceCategory = (PreferenceCategory) findPreference(
                getString(R.string.pref_notification_other_category));
        NotificationsSettingsDialogPreference devicePreference = new NotificationsSettingsDialogPreference(
                getActivity(), null, Channel.WPCOM, NotificationsSettings.Type.DEVICE, 0, mNotificationsSettings,
                mOnSettingsChangedListener
        );
        devicePreference.setTitle(R.string.notification_settings_item_other_account_emails);
        devicePreference.setDialogTitle(R.string.notification_settings_item_other_account_emails);
        devicePreference.setSummary(R.string.notification_settings_item_other_account_emails_summary);
        otherPreferenceCategory.addPreference(devicePreference);
    }

    private void addPreferencesForPreferenceScreen(PreferenceScreen preferenceScreen, Channel channel, long blogId) {
        Context context = getActivity();
        if (context == null) {
            return;
        }

        PreferenceCategory rootCategory = new PreferenceCategory(context);
        rootCategory.setTitle(R.string.notification_types);
        preferenceScreen.addPreference(rootCategory);

        NotificationsSettingsDialogPreference timelinePreference = new NotificationsSettingsDialogPreference(
                context, null, channel, NotificationsSettings.Type.TIMELINE, blogId, mNotificationsSettings,
                mOnSettingsChangedListener
        );
        timelinePreference.setIcon(R.drawable.ic_bell_grey_24dp);
        timelinePreference.setTitle(R.string.notifications_tab);
        timelinePreference.setDialogTitle(R.string.notifications_tab);
        timelinePreference.setSummary(R.string.notifications_tab_summary);
        rootCategory.addPreference(timelinePreference);

        NotificationsSettingsDialogPreference emailPreference = new NotificationsSettingsDialogPreference(
                context, null, channel, NotificationsSettings.Type.EMAIL, blogId, mNotificationsSettings,
                mOnSettingsChangedListener
        );
        emailPreference.setIcon(R.drawable.ic_mail_grey_24dp);
        emailPreference.setTitle(R.string.email);
        emailPreference.setDialogTitle(R.string.email);
        emailPreference.setSummary(R.string.notifications_email_summary);
        rootCategory.addPreference(emailPreference);

        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        String deviceID = settings.getString(NotificationsUtils.WPCOM_PUSH_DEVICE_SERVER_ID, null);
        if (!TextUtils.isEmpty(deviceID)) {
            NotificationsSettingsDialogPreference devicePreference = new NotificationsSettingsDialogPreference(
                    context, null, channel, NotificationsSettings.Type.DEVICE, blogId, mNotificationsSettings,
                    mOnSettingsChangedListener
            );
            devicePreference.setIcon(R.drawable.ic_phone_grey_24dp);
            devicePreference.setTitle(R.string.app_notifications);
            devicePreference.setDialogTitle(R.string.app_notifications);
            devicePreference.setSummary(R.string.notifications_push_summary);
            devicePreference.setEnabled(mNotificationsEnabled);
            rootCategory.addPreference(devicePreference);
        }

        mTypePreferenceCategories.add(rootCategory);
    }

    private void addSitesForViewAllSitesScreen(PreferenceScreen preferenceScreen) {
        Context context = getActivity();
        if (context == null) {
            return;
        }

        PreferenceCategory rootCategory = new PreferenceCategory(context);
        rootCategory.setTitle(R.string.notification_settings_category_your_sites);
        preferenceScreen.addPreference(rootCategory);

        configureBlogsSettings(rootCategory, true);
    }

    private final NotificationsSettingsDialogPreference.OnNotificationsSettingsChangedListener
            mOnSettingsChangedListener =
            new NotificationsSettingsDialogPreference.OnNotificationsSettingsChangedListener() {
                @SuppressWarnings("unchecked")
                @Override
                public void onSettingsChanged(Channel channel, NotificationsSettings.Type type, long blogId,
                                              JSONObject newValues) {
                    if (!isAdded()) {
                        return;
                    }

                    // Construct a new settings JSONObject to send back to WP.com
                    JSONObject settingsObject = new JSONObject();
                    switch (channel) {
                        case BLOGS:
                            try {
                                JSONObject blogObject = new JSONObject();
                                blogObject.put(NotificationsSettings.KEY_BLOG_ID, blogId);

                                JSONArray blogsArray = new JSONArray();
                                if (type == Type.DEVICE) {
                                    newValues.put(NotificationsSettings.KEY_DEVICE_ID, Long.parseLong(mDeviceId));
                                    JSONArray devicesArray = new JSONArray();
                                    devicesArray.put(newValues);
                                    blogObject.put(NotificationsSettings.KEY_DEVICES, devicesArray);
                                    blogsArray.put(blogObject);
                                } else {
                                    blogObject.put(type.toString(), newValues);
                                    blogsArray.put(blogObject);
                                }

                                settingsObject.put(NotificationsSettings.KEY_BLOGS, blogsArray);
                            } catch (JSONException e) {
                                AppLog.e(T.NOTIFS, "Could not build notification settings object");
                            }
                            break;
                        case OTHER:
                            try {
                                JSONObject otherObject = new JSONObject();
                                if (type == Type.DEVICE) {
                                    newValues.put(NotificationsSettings.KEY_DEVICE_ID, Long.parseLong(mDeviceId));
                                    JSONArray devicesArray = new JSONArray();
                                    devicesArray.put(newValues);
                                    otherObject.put(NotificationsSettings.KEY_DEVICES, devicesArray);
                                } else {
                                    otherObject.put(type.toString(), newValues);
                                }

                                settingsObject.put(NotificationsSettings.KEY_OTHER, otherObject);
                            } catch (JSONException e) {
                                AppLog.e(T.NOTIFS, "Could not build notification settings object");
                            }
                            break;
                        case WPCOM:
                            try {
                                settingsObject.put(NotificationsSettings.KEY_WPCOM, newValues);
                            } catch (JSONException e) {
                                AppLog.e(T.NOTIFS, "Could not build notification settings object");
                            }
                            break;
                    }

                    if (settingsObject.length() > 0) {
                        WordPress.getRestClientUtilsV1_1()
                                 .post("/me/notifications/settings", settingsObject, null, null, null);
                    }
                }
            };

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, @NonNull Preference preference) {
        super.onPreferenceTreeClick(preferenceScreen, preference);

        if (preference instanceof PreferenceScreen) {
            Dialog prefDialog = ((PreferenceScreen) preference).getDialog();
            if (prefDialog != null) {
                String title = String.valueOf(preference.getTitle());
                WPActivityUtils.addToolbarToDialog(this, prefDialog, title);
            }
            AnalyticsTracker.track(AnalyticsTracker.Stat.NOTIFICATION_SETTINGS_STREAMS_OPENED);
        } else {
            AnalyticsTracker.track(AnalyticsTracker.Stat.NOTIFICATION_SETTINGS_DETAILS_OPENED);
        }

        return false;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals(getString(R.string.pref_key_notification_pending_drafts))) {
            if (getActivity() != null) {
                SharedPreferences prefs =
                        android.support.v7.preference.PreferenceManager.getDefaultSharedPreferences(getActivity());
                boolean shouldNotifyOfPendingDrafts = prefs.getBoolean("wp_pref_notification_pending_drafts", true);
                if (shouldNotifyOfPendingDrafts) {
                    AnalyticsTracker.track(AnalyticsTracker.Stat.NOTIFICATION_PENDING_DRAFTS_SETTINGS_ENABLED);
                } else {
                    AnalyticsTracker.track(AnalyticsTracker.Stat.NOTIFICATION_PENDING_DRAFTS_SETTINGS_DISABLED);
                }
            }
        } else if (key.equals(getString(R.string.wp_pref_custom_notification_sound))) {
            final String defaultPath =
                    getString(R.string.notification_settings_item_sights_and_sounds_choose_sound_default);
            final String value = sharedPreferences.getString(key, defaultPath);

            if (value.trim().toLowerCase(Locale.ROOT).startsWith("file://")) {
                // sound path begins with 'file://` which will lead to FileUriExposedException when used. Revert to
                //  default and let the user know.
                AppLog.w(T.NOTIFS, "Notification sound starts with unacceptable scheme: " + value);

                Context context = WordPress.getContext();
                if (context != null) {
                    // let the user know we won't be using the selected sound
                    ToastUtils.showToast(context, R.string.notification_sound_has_invalid_path, Duration.LONG);
                }
            }
        }
    }
}
