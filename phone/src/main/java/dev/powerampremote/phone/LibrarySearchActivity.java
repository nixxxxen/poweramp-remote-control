package dev.powerampremote.phone;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import java.util.ArrayList;
import java.util.List;

/** First paged Phone Library/Search UI, backed only by PhoneConnectionService. */
public final class LibrarySearchActivity extends LocaleAwareActivity
        implements PhoneConnectionService.Listener {
    static final String EXTRA_TAB = "dev.powerampremote.phone.extra.LIBRARY_TAB";
    private static final String STATE_TAB = "library_tab";
    private static final String STATE_QUERY = "library_query";
    private static final String STATE_SEARCH_FIRST = "search_first";
    private static final String STATE_SEARCH_TOP = "search_top";
    private static final long SEARCH_DEBOUNCE_MILLISECONDS = 300L;

    private enum LocalAction {
        ALL_TRACKS, ARTISTS, ALBUMS, FOLDERS, PLAYLISTS, FOLDER_TRACKS, SUBFOLDERS
    }

    private static final class BrowseRow {
        final LibraryItem item;
        final LocalAction action;
        final int labelResource;
        final long folderId;

        private BrowseRow(
                LibraryItem item,
                LocalAction action,
                int labelResource,
                long folderId
        ) {
            this.item = item;
            this.action = action;
            this.labelResource = labelResource;
            this.folderId = folderId;
        }

        static BrowseRow item(LibraryItem item) {
            return new BrowseRow(item, null, 0, 0L);
        }

        static BrowseRow action(LocalAction action, int labelResource) {
            return new BrowseRow(null, action, labelResource, 0L);
        }

        static BrowseRow folderAction(
                LocalAction action, int labelResource, long folderId
        ) {
            return new BrowseRow(null, action, labelResource, folderId);
        }
    }

    private static final class Level {
        final String title;
        final LibraryRequest request;
        final List<BrowseRow> localRows;
        final String representativeType;
        final long representativeId;
        final LibraryPager pager = new LibraryPager();
        int firstVisible;
        int topOffset;
        boolean loading;
        RemoteClientController.LibraryFailure failure;

        Level(String title, LibraryRequest request, List<BrowseRow> localRows) {
            this(title, request, localRows, null, 0L);
        }

        Level(
                String title,
                LibraryRequest request,
                List<BrowseRow> localRows,
                String representativeType,
                long representativeId
        ) {
            this.title = title;
            this.request = request;
            this.localRows = localRows;
            this.representativeType = representativeType;
            this.representativeId = representativeId;
        }

        boolean local() {
            return request == null;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Level> libraryStack = new ArrayList<>();
    private final LibraryPager searchPager = new LibraryPager();
    private final SearchRequestGate searchGate = new SearchRequestGate();

    private TextView titleView;
    private EditText searchInput;
    private ListView listView;
    private ProgressBar progress;
    private TextView statusMessage;
    private Button actionButton;
    private BrowseAdapter adapter;
    private PhoneConnectionService.LocalBinder controller;
    private BottomNavigation.Tab selectedTab = BottomNavigation.Tab.LIBRARY;
    private RemoteClientController.Status connectionStatus =
            RemoteClientController.Status.SEARCHING;
    private boolean bindingRequested;
    private boolean started;
    private boolean restoringSearchText;
    private int knownConnectionGeneration = Integer.MIN_VALUE;
    private String knownServerId;
    private long libraryRequestSerial;
    private boolean searchLoading;
    private RemoteClientController.LibraryFailure searchFailure;
    private int searchFirstVisible;
    private int searchTopOffset;
    private int thumbnailPlaceholderPadding;
    private Runnable debounceRunnable;
    private Object renderedSource;
    private int artworkBindingLifecycle;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!(service instanceof PhoneConnectionService.LocalBinder)) {
                showDisconnected();
                return;
            }
            controller = (PhoneConnectionService.LocalBinder) service;
            controller.addListener(LibrarySearchActivity.this);
            adapter.notifyDataSetChanged();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            controller = null;
            connectionStatus = RemoteClientController.Status.ERROR;
            invalidateRequests(false);
            render();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            controller = null;
            connectionStatus = RemoteClientController.Status.ERROR;
            invalidateRequests(false);
            render();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SafeDrawingInsets.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_library_search);
        SafeDrawingInsets.apply(findViewById(R.id.library_screen_root));
        titleView = findViewById(R.id.library_screen_title);
        searchInput = findViewById(R.id.library_search_input);
        listView = findViewById(R.id.library_list);
        progress = findViewById(R.id.library_progress);
        statusMessage = findViewById(R.id.library_status_message);
        actionButton = findViewById(R.id.library_action_button);
        thumbnailPlaceholderPadding = Math.round(
                10f * getResources().getDisplayMetrics().density
        );
        adapter = new BrowseAdapter();
        listView.setAdapter(adapter);
        libraryStack.add(libraryRoot());

        String tabName = savedInstanceState == null
                ? getIntent().getStringExtra(EXTRA_TAB)
                : savedInstanceState.getString(STATE_TAB);
        if (BottomNavigation.Tab.SEARCH.name().equals(tabName)) {
            selectedTab = BottomNavigation.Tab.SEARCH;
        }
        String savedQuery = savedInstanceState == null
                ? "" : savedInstanceState.getString(STATE_QUERY, "");
        searchFirstVisible = savedInstanceState == null
                ? 0 : savedInstanceState.getInt(STATE_SEARCH_FIRST, 0);
        searchTopOffset = savedInstanceState == null
                ? 0 : savedInstanceState.getInt(STATE_SEARCH_TOP, 0);
        restoringSearchText = true;
        searchInput.setText(savedQuery);
        searchInput.setSelection(savedQuery.length());
        restoringSearchText = false;

        findViewById(R.id.library_back_button).setOnClickListener(view -> {
            haptic(view);
            navigateBack();
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            BrowseRow row = adapter.getItem(position);
            if (row != null) {
                haptic(view);
                openRow(row);
            }
        });
        listView.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(
                    android.widget.AbsListView view, int scrollState
            ) { }

            @Override
            public void onScroll(
                    android.widget.AbsListView view,
                    int firstVisibleItem,
                    int visibleItemCount,
                    int totalItemCount
            ) {
                if (visibleItemCount > 0 && firstVisibleItem + visibleItemCount
                        >= totalItemCount - 2) {
                    loadMoreIfAvailable();
                }
            }
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!restoringSearchText) scheduleSearch();
            }
            @Override public void afterTextChanged(Editable editable) { }
        });
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            runSearchNow();
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            return true;
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { navigateBack(); }
        });

        try {
            PhoneConnectionService.start(this);
        } catch (RuntimeException ignored) {
            connectionStatus = RemoteClientController.Status.ERROR;
        }
        showTab(selectedTab);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String tabName = intent.getStringExtra(EXTRA_TAB);
        showTab(BottomNavigation.Tab.SEARCH.name().equals(tabName)
                ? BottomNavigation.Tab.SEARCH : BottomNavigation.Tab.LIBRARY);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        bindingRequested = bindService(
                PhoneConnectionService.bindingIntent(this),
                serviceConnection,
                Context.BIND_AUTO_CREATE
        );
        if (!bindingRequested) showDisconnected();
    }

    @Override
    protected void onStop() {
        saveScrollPosition();
        started = false;
        artworkBindingLifecycle++;
        cancelDebounce();
        libraryRequestSerial++;
        searchGate.invalidate();
        searchLoading = false;
        for (Level level : libraryStack) level.loading = false;
        if (controller != null) {
            controller.removeListener(this);
            controller = null;
        }
        if (bindingRequested) {
            unbindService(serviceConnection);
            bindingRequested = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        searchGate.invalidate();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        saveScrollPosition();
        outState.putString(STATE_TAB, selectedTab.name());
        outState.putString(STATE_QUERY, searchInput.getText().toString());
        outState.putInt(STATE_SEARCH_FIRST, searchFirstVisible);
        outState.putInt(STATE_SEARCH_TOP, searchTopOffset);
        super.onSaveInstanceState(outState);
    }

    void showTab(BottomNavigation.Tab tab) {
        if (tab != BottomNavigation.Tab.LIBRARY && tab != BottomNavigation.Tab.SEARCH) return;
        saveScrollPosition();
        selectedTab = tab;
        BottomNavigation.bind(this, selectedTab);
        searchInput.setVisibility(tab == BottomNavigation.Tab.SEARCH ? View.VISIBLE : View.GONE);
        if (tab == BottomNavigation.Tab.LIBRARY) {
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (keyboard != null) {
                keyboard.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            }
            searchInput.clearFocus();
        }
        if (tab == BottomNavigation.Tab.SEARCH
                && connected()
                && !normalizedQuery().isEmpty()
                && !searchPager.initialized()
                && !searchLoading) {
            runSearchNow();
        } else {
            render();
        }
    }

    private Level libraryRoot() {
        List<BrowseRow> rows = new ArrayList<>();
        rows.add(BrowseRow.action(LocalAction.ALL_TRACKS, R.string.library_all_tracks));
        rows.add(BrowseRow.action(LocalAction.ARTISTS, R.string.library_artists));
        rows.add(BrowseRow.action(LocalAction.ALBUMS, R.string.library_albums));
        rows.add(BrowseRow.action(LocalAction.FOLDERS, R.string.library_folders));
        rows.add(BrowseRow.action(LocalAction.PLAYLISTS, R.string.library_playlists));
        return new Level(getString(R.string.nav_library), null, rows);
    }

    private Level folderMenu(long folderId, String title) {
        List<BrowseRow> rows = new ArrayList<>();
        rows.add(BrowseRow.folderAction(
                LocalAction.FOLDER_TRACKS, R.string.library_folder_tracks, folderId
        ));
        rows.add(BrowseRow.folderAction(
                LocalAction.SUBFOLDERS, R.string.library_subfolders, folderId
        ));
        return new Level(title, null, rows);
    }

    private void openRow(BrowseRow row) {
        if (row.action != null) {
            openLocalAction(row);
            return;
        }
        LibraryItem item = row.item;
        if (item == null) return;
        switch (item.type) {
            case "artist":
                pushNetwork(
                        displayTitle(item),
                        LibraryRequest.artistTracks(item.id),
                        item.type,
                        item.id
                );
                break;
            case "album":
                pushNetwork(
                        displayTitle(item),
                        LibraryRequest.albumTracks(item.id),
                        item.type,
                        item.id
                );
                break;
            case "playlist":
                pushNetwork(
                        displayTitle(item),
                        LibraryRequest.playlistTracks(item.id),
                        item.type,
                        item.id
                );
                break;
            case "folder":
                push(folderMenu(item.id, displayTitle(item)));
                break;
            default:
                if (item.playTarget != null) play(item.playTarget);
                break;
        }
    }

    private void openLocalAction(BrowseRow row) {
        switch (row.action) {
            case ALL_TRACKS:
                pushNetwork(getString(R.string.library_all_tracks), LibraryRequest.tracks());
                break;
            case ARTISTS:
                pushNetwork(getString(R.string.library_artists), LibraryRequest.artists());
                break;
            case ALBUMS:
                pushNetwork(getString(R.string.library_albums), LibraryRequest.albums());
                break;
            case FOLDERS:
                pushNetwork(getString(R.string.library_folders), LibraryRequest.subfolders(0L));
                break;
            case PLAYLISTS:
                pushNetwork(getString(R.string.library_playlists), LibraryRequest.playlists());
                break;
            case FOLDER_TRACKS:
                pushNetwork(
                        getString(R.string.library_folder_tracks),
                        LibraryRequest.folderTracks(row.folderId),
                        RepresentativeArtworkKey.TYPE_FOLDER,
                        row.folderId
                );
                break;
            case SUBFOLDERS:
                pushNetwork(
                        getString(R.string.library_subfolders),
                        LibraryRequest.subfolders(row.folderId)
                );
                break;
            default:
                break;
        }
    }

    private void pushNetwork(String title, LibraryRequest request) {
        push(new Level(title, request, null));
    }

    private void pushNetwork(
            String title,
            LibraryRequest request,
            String representativeType,
            long representativeId
    ) {
        push(new Level(
                title, request, null, representativeType, representativeId
        ));
    }

    private void push(Level level) {
        saveScrollPosition();
        currentLevel().loading = false; // Its outstanding append will be invalidated below.
        libraryStack.add(level);
        libraryRequestSerial++;
        render();
        if (!level.local()) loadLibraryPage(level, false);
    }

    private void navigateBack() {
        if (selectedTab == BottomNavigation.Tab.LIBRARY && libraryStack.size() > 1) {
            saveScrollPosition();
            libraryRequestSerial++;
            libraryStack.remove(libraryStack.size() - 1);
            render();
            return;
        }
        finish();
    }

    private void scheduleSearch() {
        cancelDebounce();
        searchGate.invalidate();
        searchPager.reset();
        searchFirstVisible = 0;
        searchTopOffset = 0;
        renderedSource = null;
        searchFailure = null;
        searchLoading = false;
        render();
        debounceRunnable = this::runSearchNow;
        handler.postDelayed(debounceRunnable, SEARCH_DEBOUNCE_MILLISECONDS);
    }

    private void runSearchNow() {
        cancelDebounce();
        if (selectedTab != BottomNavigation.Tab.SEARCH) return;
        String query = normalizedQuery();
        if (query.isEmpty()) {
            searchGate.invalidate();
            searchPager.reset();
            searchLoading = false;
            searchFailure = null;
            render();
            return;
        }
        loadSearchPage(false);
    }

    private void loadMoreIfAvailable() {
        if (!connected()) return;
        saveScrollPosition();
        if (selectedTab == BottomNavigation.Tab.SEARCH) {
            if (searchPager.canLoadMore() && !searchLoading && searchFailure == null) {
                loadSearchPage(true);
            }
            return;
        }
        Level level = currentLevel();
        if (!level.local() && level.pager.canLoadMore() && !level.loading
                && level.failure == null) {
            loadLibraryPage(level, true);
        }
    }

    private void loadLibraryPage(Level level, boolean append) {
        if (controller == null || !connected()) {
            level.failure = RemoteClientController.LibraryFailure.DISCONNECTED;
            render();
            return;
        }
        if (level.loading) return;
        if (!append) {
            level.pager.reset();
            level.firstVisible = 0;
            level.topOffset = 0;
            renderedSource = null;
        }
        String requestedToken = append ? level.pager.nextPageToken() : null;
        level.loading = true;
        level.failure = null;
        long serial = ++libraryRequestSerial;
        int generation = controller.libraryConnectionGeneration();
        render();
        controller.requestLibraryPage(level.request, requestedToken, (resultGeneration, page, failure) -> {
            if (!started || serial != libraryRequestSerial || level != currentLevel()
                    || controller == null
                    || resultGeneration != controller.libraryConnectionGeneration()
                    || resultGeneration != generation) {
                return;
            }
            level.loading = false;
            level.failure = failure;
            if (failure == null && page != null) {
                try {
                    level.pager.accept(requestedToken, page);
                    if (!append && page.offset == 0 && level.representativeType != null) {
                        RepresentativeArtworkKey key = controller.representativeArtworkKey(
                                level.representativeType, level.representativeId
                        );
                        controller.rememberRepresentativeCandidates(key, page.items);
                    }
                } catch (IllegalArgumentException exception) {
                    level.failure = RemoteClientController.LibraryFailure.SERVER_ERROR;
                }
            }
            if (selectedTab == BottomNavigation.Tab.LIBRARY) render();
        });
    }

    private void loadSearchPage(boolean append) {
        if (controller == null || !connected()) {
            searchFailure = RemoteClientController.LibraryFailure.DISCONNECTED;
            render();
            return;
        }
        if (searchLoading) return;
        String query = normalizedQuery();
        if (query.isEmpty()) return;
        if (!append) {
            if (searchPager.initialized()) {
                searchFirstVisible = 0;
                searchTopOffset = 0;
            }
            searchPager.reset();
            renderedSource = null;
        }
        String requestedToken = append ? searchPager.nextPageToken() : null;
        int generation = controller.libraryConnectionGeneration();
        SearchRequestGate.Request request = searchGate.begin(query, generation);
        searchLoading = true;
        searchFailure = null;
        render();
        LibraryRequest apiRequest;
        try {
            apiRequest = LibraryRequest.search(query);
        } catch (IllegalArgumentException exception) {
            searchLoading = false;
            searchFailure = RemoteClientController.LibraryFailure.SERVER_ERROR;
            render();
            return;
        }
        controller.requestLibraryPage(apiRequest, requestedToken, (resultGeneration, page, failure) -> {
            if (!started || controller == null || !searchGate.accepts(
                    request, normalizedQuery(), controller.libraryConnectionGeneration()
            ) || resultGeneration != generation) {
                return;
            }
            searchLoading = false;
            searchFailure = failure;
            if (failure == null && page != null) {
                try {
                    searchPager.accept(requestedToken, page);
                } catch (IllegalArgumentException exception) {
                    searchFailure = RemoteClientController.LibraryFailure.SERVER_ERROR;
                }
            }
            if (selectedTab == BottomNavigation.Tab.SEARCH) render();
        });
    }

    private void play(LibraryPlayTarget target) {
        if (controller == null || !connected()) {
            Toast.makeText(this, R.string.library_disconnected, Toast.LENGTH_SHORT).show();
            return;
        }
        int generation = controller.libraryConnectionGeneration();
        controller.playLibraryTarget(target, (resultGeneration, failure) -> {
            if (!started || controller == null || resultGeneration != generation
                    || resultGeneration != controller.libraryConnectionGeneration()) return;
            if (failure != null) {
                Toast.makeText(this, failureMessage(failure), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void render() {
        Object source = selectedTab == BottomNavigation.Tab.SEARCH ? searchPager : currentLevel();
        boolean changedSource = renderedSource != source;
        if (!changedSource) saveScrollPosition();
        BottomNavigation.bind(this, selectedTab);
        boolean connected = connected();
        findViewById(R.id.library_back_button).setVisibility(
                selectedTab == BottomNavigation.Tab.LIBRARY && libraryStack.size() > 1
                        ? View.VISIBLE : View.INVISIBLE
        );
        titleView.setText(selectedTab == BottomNavigation.Tab.SEARCH
                ? getString(R.string.nav_search) : currentLevel().title);
        searchInput.setVisibility(
                selectedTab == BottomNavigation.Tab.SEARCH ? View.VISIBLE : View.GONE
        );
        progress.setVisibility(View.GONE);
        statusMessage.setVisibility(View.GONE);
        actionButton.setVisibility(View.GONE);
        actionButton.setOnClickListener(null);

        if (selectedTab == BottomNavigation.Tab.SEARCH) renderSearch();
        else renderLibrary();
        if (!connected) {
            progress.setVisibility(View.GONE);
            actionButton.setVisibility(View.GONE);
            showMessage(R.string.library_disconnected);
        }
        // Appending/status updates must let ListView retain its live fling/position.
        // Restore only when navigating to a different list, never from a posted stale callback.
        renderedSource = source;
        if (changedSource) {
            if (selectedTab == BottomNavigation.Tab.SEARCH) {
                restoreListPosition(searchFirstVisible, searchTopOffset);
            } else {
                Level level = currentLevel();
                restoreListPosition(level.firstVisible, level.topOffset);
            }
        }
    }

    private void renderLibrary() {
        Level level = currentLevel();
        if (level.local()) {
            adapter.setRows(level.localRows);
            listView.setVisibility(View.VISIBLE);
            return;
        }
        renderNetworkPage(
                level.pager,
                level.loading,
                level.failure,
                false,
                () -> loadLibraryPage(level, level.pager.initialized()
                        && level.failure != RemoteClientController.LibraryFailure.PAGE_EXPIRED)
        );
    }

    private void renderSearch() {
        String query = normalizedQuery();
        if (query.isEmpty()) {
            adapter.setRows(new ArrayList<>());
            listView.setVisibility(View.GONE);
            showMessage(R.string.library_search_prompt);
            return;
        }
        renderNetworkPage(
                searchPager,
                searchLoading,
                searchFailure,
                true,
                () -> loadSearchPage(searchPager.initialized()
                        && searchFailure != RemoteClientController.LibraryFailure.PAGE_EXPIRED)
        );
    }

    private void renderNetworkPage(
            LibraryPager pager,
            boolean loading,
            RemoteClientController.LibraryFailure failure,
            boolean search,
            Runnable retry
    ) {
        List<BrowseRow> rows = new ArrayList<>();
        for (LibraryItem item : pager.items()) rows.add(BrowseRow.item(item));
        adapter.setRows(rows);
        listView.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        if (failure != null) {
            showMessage(failureMessage(failure));
            if (failure != RemoteClientController.LibraryFailure.UNSUPPORTED) {
                showAction(failure == RemoteClientController.LibraryFailure.PAGE_EXPIRED
                        ? R.string.library_reload : R.string.library_retry, retry);
            }
            return;
        }
        if (loading) {
            progress.setVisibility(View.VISIBLE);
            if (rows.isEmpty()) showMessage(R.string.library_loading);
        } else if (pager.initialized() && rows.isEmpty()) {
            showMessage(search ? R.string.library_search_empty : R.string.library_empty);
        } else if (pager.truncated()) {
            showMessage(R.string.library_truncated);
        }
        if (!loading && pager.canLoadMore()) {
            showAction(R.string.library_more, retry);
        }
    }

    private void showMessage(int resource) {
        statusMessage.setText(resource);
        statusMessage.setVisibility(View.VISIBLE);
    }

    private void showAction(int label, Runnable action) {
        actionButton.setText(label);
        actionButton.setVisibility(View.VISIBLE);
        actionButton.setOnClickListener(view -> {
            haptic(view);
            action.run();
        });
    }

    private int failureMessage(RemoteClientController.LibraryFailure failure) {
        switch (failure) {
            case DISCONNECTED:
                return R.string.library_disconnected;
            case PERMISSION_REQUIRED:
                return R.string.library_permission_required;
            case UNSUPPORTED:
                return R.string.library_unsupported;
            case PROVIDER_UNAVAILABLE:
                return R.string.library_provider_unavailable;
            case PAGE_EXPIRED:
                return R.string.library_page_expired;
            case AUTHENTICATION:
            case SERVER_ERROR:
            default:
                return R.string.library_error;
        }
    }

    private void saveScrollPosition() {
        Object source = selectedTab == BottomNavigation.Tab.SEARCH ? searchPager : currentLevel();
        if (renderedSource != source || listView == null || listView.getChildCount() == 0
                || listView.getVisibility() != View.VISIBLE) return;
        int first = listView.getFirstVisiblePosition();
        int top = listView.getChildAt(0).getTop();
        if (selectedTab == BottomNavigation.Tab.SEARCH) {
            searchFirstVisible = first;
            searchTopOffset = top;
        } else {
            Level level = currentLevel();
            level.firstVisible = first;
            level.topOffset = top;
        }
    }

    private void restoreListPosition(int first, int top) {
        listView.setSelectionFromTop(first, top);
    }

    private String normalizedQuery() {
        return searchInput.getText().toString().trim();
    }

    private Level currentLevel() {
        return libraryStack.get(libraryStack.size() - 1);
    }

    private boolean connected() {
        return connectionStatus == RemoteClientController.Status.CONNECTED
                || connectionStatus == RemoteClientController.Status.CONNECTED_DIRECT;
    }

    private void showDisconnected() {
        connectionStatus = RemoteClientController.Status.ERROR;
        render();
    }

    private void invalidateRequests(boolean serverChanged) {
        artworkBindingLifecycle++;
        libraryRequestSerial++;
        searchGate.invalidate();
        searchLoading = false;
        for (Level level : libraryStack) level.loading = false;
        if (serverChanged) {
            libraryStack.clear();
            libraryStack.add(libraryRoot());
            searchPager.reset();
            searchFailure = null;
        }
    }

    private void cancelDebounce() {
        if (debounceRunnable != null) {
            handler.removeCallbacks(debounceRunnable);
            debounceRunnable = null;
        }
    }

    private String displayTitle(LibraryItem item) {
        return item.title == null || item.title.trim().isEmpty()
                ? getString(R.string.library_unknown_item) : item.title;
    }

    private void requestArtwork(
            Holder holder,
            String path,
            LibraryArtworkKey key,
            LibraryArtworkBindingGate.Request binding
    ) {
        if (path == null || key == null || controller == null) return;
        int generation = controller.libraryConnectionGeneration();
        controller.requestLibraryArtwork(path, (resultGeneration, resultKey, artwork, failure) -> {
            if (!started || controller == null || resultGeneration != generation
                    || resultGeneration != controller.libraryConnectionGeneration()
                    || !holder.artworkGate.complete(binding, resultKey, artwork != null)) return;
            if (artwork != null) {
                holder.artwork.setPadding(0, 0, 0, 0);
                holder.artwork.setImageBitmap(artwork);
            }
        });
    }

    private void requestRepresentativeArtwork(
            Holder holder,
            RepresentativeArtworkKey key,
            LibraryArtworkBindingGate.Request binding
    ) {
        if (key == null || controller == null) return;
        int generation = controller.libraryConnectionGeneration();
        controller.requestRepresentativeArtwork(
                key,
                (resultGeneration, resultKey, artwork) -> {
                    if (!started || controller == null || resultGeneration != generation
                            || resultGeneration != controller.libraryConnectionGeneration()
                            || !holder.artworkGate.complete(binding, resultKey, artwork != null)) return;
                    if (artwork != null) {
                        holder.artwork.setPadding(0, 0, 0, 0);
                        holder.artwork.setImageBitmap(artwork);
                    }
                }
        );
    }

    @Override
    public void onStatusChanged(
            RemoteClientController.Status status, long retryDelayMilliseconds
    ) {
        int generation = controller == null
                ? Integer.MIN_VALUE : controller.libraryConnectionGeneration();
        String serverId = controller == null
                ? null : controller.playerDeviceSnapshot().serverId;
        boolean serverChanged = knownServerId != null && serverId != null
                && !knownServerId.equals(serverId);
        boolean generationChanged = generation != knownConnectionGeneration;
        boolean wasConnected = connected();
        connectionStatus = status;
        if (serverId != null) knownServerId = serverId;
        if (generationChanged) {
            knownConnectionGeneration = generation;
            invalidateRequests(serverChanged);
            adapter.notifyDataSetChanged();
        }
        if (connected() && (!wasConnected || generationChanged)) {
            // Same-Server reconnect is not a new browse session. Keep loaded pages and offsets.
            if (searchFailure == RemoteClientController.LibraryFailure.DISCONNECTED) {
                searchFailure = null;
            }
            for (Level level : libraryStack) {
                if (level.failure == RemoteClientController.LibraryFailure.DISCONNECTED) {
                    level.failure = null;
                }
            }
            if (selectedTab == BottomNavigation.Tab.SEARCH) {
                if (!normalizedQuery().isEmpty() && !searchPager.initialized()
                        && searchFailure == null) runSearchNow();
                else render();
            } else {
                Level level = currentLevel();
                if (!level.local() && !level.pager.initialized() && level.failure == null) {
                    loadLibraryPage(level, false);
                }
                else render();
            }
            return;
        }
        if (connected() && selectedTab == BottomNavigation.Tab.SEARCH
                && !normalizedQuery().isEmpty()
                && !searchPager.initialized() && !searchLoading && searchFailure == null) {
            runSearchNow();
            return;
        }
        Level level = currentLevel();
        if (connected() && selectedTab == BottomNavigation.Tab.LIBRARY
                && !level.local() && !level.pager.initialized() && !level.loading
                && level.failure == null) {
            loadLibraryPage(level, false);
            return;
        }
        render();
    }

    @Override public void onPairingFailed(RemoteClientController.PairingError error) { }
    @Override public void onPairingSucceeded(String serviceName) { }
    @Override public void onStateChanged(RemoteState state, long receivedRealtimeMilliseconds) { }
    @Override public void onArtworkChanged(Bitmap artwork) { }
    @Override public void onCommandError(boolean authenticationError) { }
    @Override public void onPlaybackSnapshot(PlaybackUiSnapshot snapshot) { }

    private static void haptic(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private final class BrowseAdapter extends BaseAdapter {
        private List<BrowseRow> rows = new ArrayList<>();

        void setRows(List<BrowseRow> rows) {
            if (this.rows.size() == rows.size()) {
                boolean same = true;
                for (int i = 0; i < rows.size(); i++) {
                    BrowseRow old = this.rows.get(i);
                    BrowseRow next = rows.get(i);
                    if (old.item != next.item || old.action != next.action
                            || old.labelResource != next.labelResource || old.folderId != next.folderId) {
                        same = false;
                        break;
                    }
                }
                if (same) return;
            }
            this.rows = rows;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return rows.size(); }
        @Override public BrowseRow getItem(int position) {
            return position < 0 || position >= rows.size() ? null : rows.get(position);
        }
        @Override public long getItemId(int position) {
            BrowseRow row = getItem(position);
            return row == null || row.item == null ? position : row.item.id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(LibrarySearchActivity.this)
                        .inflate(R.layout.library_list_item, parent, false);
                holder = new Holder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (Holder) convertView.getTag();
            }
            BrowseRow row = getItem(position);
            if (row == null) return convertView;
            if (row.item == null) {
                holder.artworkGate.bind(null);
                holder.title.setText(row.labelResource);
                setOptional(holder.subtitle, null);
                setOptional(holder.detail, null);
                holder.artwork.setVisibility(View.INVISIBLE);
                return convertView;
            }
            LibraryItem item = row.item;
            holder.title.setText(displayTitle(item));
            ArrayList<String> subtitle = new ArrayList<>(2);
            addDistinct(subtitle, item.artist);
            addDistinct(subtitle, item.album);
            setOptional(holder.subtitle, join(subtitle));
            ArrayList<String> detail = new ArrayList<>(2);
            if (item.trackCount != null) {
                detail.add(getResources().getQuantityString(
                        R.plurals.library_track_count, item.trackCount, item.trackCount
                ));
            }
            if (item.durationMilliseconds != null) {
                long seconds = item.durationMilliseconds / 1_000L;
                detail.add(TimeFormatter.formatSeconds((int) Math.min(seconds, Integer.MAX_VALUE)));
            }
            setOptional(holder.detail, join(detail));
            boolean track = "track".equals(item.type)
                    || "playlist_entry".equals(item.type)
                    || "queue_entry".equals(item.type);
            boolean representative = RepresentativeArtworkKey.isSupportedType(item.type);
            if (!track && !representative) {
                holder.artworkGate.bind(null);
                holder.artwork.setVisibility(View.INVISIBLE);
            } else if (track) {
                holder.artwork.setVisibility(View.VISIBLE);
                LibraryArtworkKey artworkKey = controller == null
                        ? null : controller.libraryArtworkKey(item.artworkPath);
                LibraryArtworkBindingGate.Request binding =
                        holder.artworkGate.bind(artworkKey, artworkBindingLifecycle);
                Bitmap bitmap = controller == null
                        ? null : controller.cachedLibraryArtwork(artworkKey);
                if (bitmap == null) {
                    // The visible row still owns its bitmap even after the small LRU evicts it.
                    if (holder.artworkGate.artworkDisplayed()) return convertView;
                    holder.artwork.setPadding(
                            thumbnailPlaceholderPadding,
                            thumbnailPlaceholderPadding,
                            thumbnailPlaceholderPadding,
                            thumbnailPlaceholderPadding
                    );
                    holder.artwork.setImageResource(R.drawable.ic_album_placeholder);
                    if (controller != null && holder.artworkGate.begin(binding)) {
                        requestArtwork(holder, item.artworkPath, artworkKey, binding);
                    }
                } else {
                    holder.artworkGate.complete(binding, artworkKey, true);
                    holder.artwork.setPadding(0, 0, 0, 0);
                    holder.artwork.setImageBitmap(bitmap);
                }
            } else {
                holder.artwork.setVisibility(View.VISIBLE);
                RepresentativeArtworkKey representativeKey = controller == null
                        ? null : controller.representativeArtworkKey(item.type, item.id);
                LibraryArtworkBindingGate.Request binding =
                        holder.artworkGate.bind(representativeKey, artworkBindingLifecycle);
                Bitmap bitmap = controller == null
                        ? null : controller.cachedRepresentativeArtwork(representativeKey);
                if (bitmap == null) {
                    if (holder.artworkGate.artworkDisplayed()) return convertView;
                    holder.artwork.setPadding(
                            thumbnailPlaceholderPadding,
                            thumbnailPlaceholderPadding,
                            thumbnailPlaceholderPadding,
                            thumbnailPlaceholderPadding
                    );
                    holder.artwork.setImageResource(R.drawable.ic_album_placeholder);
                    if (controller != null && holder.artworkGate.begin(binding)) {
                        requestRepresentativeArtwork(holder, representativeKey, binding);
                    }
                } else {
                    holder.artworkGate.complete(binding, representativeKey, true);
                    holder.artwork.setPadding(0, 0, 0, 0);
                    holder.artwork.setImageBitmap(bitmap);
                }
            }
            return convertView;
        }
    }

    private static final class Holder {
        final ImageView artwork;
        final TextView title;
        final TextView subtitle;
        final TextView detail;
        final LibraryArtworkBindingGate artworkGate = new LibraryArtworkBindingGate();

        Holder(View view) {
            artwork = view.findViewById(R.id.library_item_artwork);
            title = view.findViewById(R.id.library_item_title);
            subtitle = view.findViewById(R.id.library_item_subtitle);
            detail = view.findViewById(R.id.library_item_detail);
        }
    }

    private static void setOptional(TextView view, String value) {
        if (value == null || value.trim().isEmpty()) {
            view.setText("");
            view.setVisibility(View.GONE);
        } else {
            view.setText(value);
            view.setVisibility(View.VISIBLE);
        }
    }

    private static void addDistinct(List<String> values, String value) {
        if (value == null || value.trim().isEmpty() || values.contains(value)) return;
        values.add(value);
    }

    private static String join(List<String> values) {
        if (values.isEmpty()) return null;
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(" · ");
            result.append(value);
        }
        return result.toString();
    }
}
