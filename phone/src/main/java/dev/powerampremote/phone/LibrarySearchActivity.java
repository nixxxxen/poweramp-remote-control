package dev.powerampremote.phone;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Build;
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
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;

/** First paged Phone Library/Search UI, backed only by PhoneConnectionService. */
public final class LibrarySearchActivity extends LocaleAwareActivity
        implements PhoneConnectionService.Listener {
    static final String EXTRA_TAB = "dev.powerampremote.phone.extra.LIBRARY_TAB";
    private static final String STATE_TAB = "library_tab";
    private static final String STATE_QUERY = "library_query";
    private static final String STATE_SEARCH_FIRST = "search_first";
    private static final String STATE_SEARCH_TOP = "search_top";
    private static final String STATE_SELECTION = "track_selection";
    private static final String STATE_QUEUE_ADD_OPERATION = "queue_add_operation";
    private static final String STATE_QUEUE_ADD_SELECTION = "queue_add_selection";
    private static final long SEARCH_DEBOUNCE_MILLISECONDS = 300L;

    private enum LocalAction {
        ALL_TRACKS, ARTISTS, ALBUMS, FOLDERS, PLAYLISTS, FOLDER_TRACKS, SUBFOLDERS
    }

    private static final class BrowseRow {
        final LibraryItem item;
        final LocalAction action;
        final int labelResource;
        final long folderId;
        final CategorizedSearchResult.SectionType searchHeader;
        final CategorizedSearchResult.SectionType searchMore;
        final String historyQuery;
        final boolean historyHeader;

        private BrowseRow(
                LibraryItem item,
                LocalAction action,
                int labelResource,
                long folderId,
                CategorizedSearchResult.SectionType searchHeader,
                CategorizedSearchResult.SectionType searchMore,
                String historyQuery,
                boolean historyHeader
        ) {
            this.item = item;
            this.action = action;
            this.labelResource = labelResource;
            this.folderId = folderId;
            this.searchHeader = searchHeader;
            this.searchMore = searchMore;
            this.historyQuery = historyQuery;
            this.historyHeader = historyHeader;
        }

        static BrowseRow item(LibraryItem item) {
            return new BrowseRow(item, null, 0, 0L, null, null, null, false);
        }

        static BrowseRow action(LocalAction action, int labelResource) {
            return new BrowseRow(
                    null, action, labelResource, 0L, null, null, null, false
            );
        }

        static BrowseRow folderAction(
                LocalAction action, int labelResource, long folderId
        ) {
            return new BrowseRow(
                    null, action, labelResource, folderId, null, null, null, false
            );
        }

        static BrowseRow searchHeader(CategorizedSearchResult.SectionType section) {
            return new BrowseRow(null, null, 0, 0L, section, null, null, false);
        }

        static BrowseRow searchMore(CategorizedSearchResult.SectionType section) {
            return new BrowseRow(null, null, 0, 0L, null, section, null, false);
        }

        static BrowseRow history(String query) {
            return new BrowseRow(null, null, 0, 0L, null, null, query, false);
        }

        static BrowseRow historyHeader() {
            return new BrowseRow(null, null, 0, 0L, null, null, null, true);
        }
    }

    private static final class SearchSectionLoad {
        boolean loading;
        long serial;
        RemoteClientController.LibraryFailure failure;
    }

    private static final class Level {
        final String title;
        LibraryRequest request;
        final List<BrowseRow> localRows;
        final String representativeType;
        final long representativeId;
        final LibraryPager pager = new LibraryPager();
        final LibrarySortRequestState sortState = new LibrarySortRequestState();
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
            if (request != null) sortState.change(request.sort);
        }

        boolean local() {
            return request == null;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Level> libraryStack = new ArrayList<>();
    private final SearchRequestGate searchGate = new SearchRequestGate();
    private final SearchOriginState searchOrigin = new SearchOriginState();
    private final Object searchRenderSource = new Object();
    private final EnumMap<CategorizedSearchResult.SectionType, SearchSectionLoad>
            searchSectionLoads = new EnumMap<>(CategorizedSearchResult.SectionType.class);

    private TextView titleView;
    private ImageButton sortCriterion;
    private ImageButton sortDirection;
    private TextView selectionCount;
    private ImageButton selectionAddQueue;
    private ImageButton selectionClose;
    private View searchContainer;
    private EditText searchInput;
    private ImageButton searchClear;
    private ListView listView;
    private ProgressBar progress;
    private TextView statusMessage;
    private Button actionButton;
    private BrowseAdapter adapter;
    private MiniPlayerController miniPlayer;
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
    private boolean searchInitialized;
    private CategorizedSearchResult searchResult;
    private RemoteClientController.LibraryFailure searchFailure;
    private int searchFirstVisible;
    private int searchTopOffset;
    private int thumbnailPlaceholderPadding;
    private java.text.DateFormat dateAddedFormat;
    private Runnable debounceRunnable;
    private Object renderedSource;
    private int artworkBindingLifecycle;
    private LibraryItem pendingSearchContainer;
    private SearchHistoryStore searchHistoryStore;
    private LibrarySortStore librarySortStore;
    private LibrarySortCapabilities librarySortCapabilities =
            LibrarySortCapabilities.defaultOnly();
    private long libraryCapabilitiesSerial;
    private int libraryCapabilitiesGeneration = Integer.MIN_VALUE;
    private List<String> searchHistory = new ArrayList<>();
    private boolean imeVisible;
    private String lastSuccessfulQueryKey;
    private long searchSectionGeneration;
    private final TrackSelection selection = new TrackSelection();
    private long queueAddOperationId;
    private boolean queueAddFromSelection;
    private boolean queueAddInFlight;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!(service instanceof PhoneConnectionService.LocalBinder)) {
                showDisconnected();
                return;
            }
            controller = (PhoneConnectionService.LocalBinder) service;
            // Discard any Activity-local identity first; the service replay below is authoritative.
            adapter.setConfirmedState(null);
            controller.addListener(LibrarySearchActivity.this);
            synchronizeQueueAddOperation();
            miniPlayer.attach(controller);
            adapter.notifyDataSetChanged();
            render();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            controller = null;
            miniPlayer.onServiceDisconnected();
            connectionStatus = RemoteClientController.Status.ERROR;
            invalidateRequests(false);
            render();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            controller = null;
            miniPlayer.onServiceDisconnected();
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
        View root = findViewById(R.id.library_screen_root);
        SafeDrawingInsets.apply(root);
        titleView = findViewById(R.id.library_screen_title);
        sortCriterion = findViewById(R.id.library_sort_criterion);
        sortDirection = findViewById(R.id.library_sort_direction);
        selectionCount = findViewById(R.id.library_selection_count);
        selectionAddQueue = findViewById(R.id.library_selection_add_queue);
        selectionClose = findViewById(R.id.library_selection_close);
        searchContainer = findViewById(R.id.library_search_container);
        searchInput = findViewById(R.id.library_search_input);
        searchClear = findViewById(R.id.library_search_clear);
        listView = findViewById(R.id.library_list);
        progress = findViewById(R.id.library_progress);
        statusMessage = findViewById(R.id.library_status_message);
        actionButton = findViewById(R.id.library_action_button);
        miniPlayer = new MiniPlayerController(this);
        thumbnailPlaceholderPadding = Math.round(
                10f * getResources().getDisplayMetrics().density
        );
        dateAddedFormat = android.text.format.DateFormat.getMediumDateFormat(this);
        adapter = new BrowseAdapter();
        listView.setAdapter(adapter);
        libraryStack.add(libraryRoot());
        searchHistoryStore = new SearchHistoryStore(this);
        librarySortStore = new LibrarySortStore(this);
        searchHistory = searchHistoryStore.load();
        for (CategorizedSearchResult.SectionType type
                : CategorizedSearchResult.SectionType.values()) {
            searchSectionLoads.put(type, new SearchSectionLoad());
        }
        ViewCompat.setOnApplyWindowInsetsListener(searchInput, (view, insets) -> {
            boolean visible = insets.isVisible(WindowInsetsCompat.Type.ime());
            if (imeVisible != visible) {
                boolean wasVisible = imeVisible;
                imeVisible = visible;
                if (wasVisible && !visible) saveSuccessfulQueryOnImeClose();
                if (selectedTab == BottomNavigation.Tab.SEARCH) render();
            }
            return insets;
        });

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
        searchClear.setVisibility(savedQuery.isEmpty() ? View.GONE : View.VISIBLE);
        if (savedInstanceState != null) {
            queueAddOperationId = savedInstanceState.getLong(
                    STATE_QUEUE_ADD_OPERATION, 0L
            );
            queueAddFromSelection = savedInstanceState.getBoolean(
                    STATE_QUEUE_ADD_SELECTION, false
            );
            queueAddInFlight = queueAddOperationId != 0L;
            ArrayList<String> savedSelection = savedInstanceState.getStringArrayList(
                    STATE_SELECTION
            );
            if (savedSelection != null) {
                for (String json : savedSelection) {
                    try {
                        selection.toggle(LibraryPlayTarget.parse(
                                new org.json.JSONObject(json)
                        ));
                    } catch (Exception ignored) {
                        selection.clear();
                        break;
                    }
                }
            }
        }

        findViewById(R.id.library_back_button).setOnClickListener(view -> {
            haptic(view);
            if (!selection.isEmpty()) clearSelection();
            else navigateBack();
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
                searchClear.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                if (!restoringSearchText) scheduleSearch();
            }
            @Override public void afterTextChanged(Editable editable) { }
        });
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            recordHistory(normalizedQuery());
            runSearchNow();
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            return true;
        });
        searchInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (selectedTab == BottomNavigation.Tab.SEARCH) render();
        });
        searchClear.setOnClickListener(view -> {
            haptic(view);
            clearSearchInput();
        });
        sortCriterion.setOnClickListener(view -> {
            haptic(view);
            showSortCriterionDialog();
        });
        sortDirection.setOnClickListener(view -> {
            haptic(view);
            toggleSortDirection();
        });
        selectionAddQueue.setOnClickListener(view -> {
            haptic(view);
            addSelectionToQueue();
        });
        selectionClose.setOnClickListener(view -> {
            haptic(view);
            clearSelection();
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!selection.isEmpty()) clearSelection();
                else navigateBack();
            }
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
        BottomNavigation.cancel(this);
        started = false;
        artworkBindingLifecycle++;
        cancelDebounce();
        libraryRequestSerial++;
        libraryCapabilitiesSerial++;
        libraryCapabilitiesGeneration = Integer.MIN_VALUE;
        queueAddInFlight = false;
        searchGate.invalidate();
        searchSectionGeneration++;
        searchLoading = false;
        for (SearchSectionLoad state : searchSectionLoads.values()) state.loading = false;
        if (selectedTab == BottomNavigation.Tab.SEARCH && pendingSearchContainer != null) {
            // A background/configuration stop can cancel the content exit before showTab().
            // Leave the still-visible Search presentation ready for another deliberate tap.
            pendingSearchContainer = null;
            searchOrigin.clear();
        }
        for (Level level : libraryStack) level.loading = false;
        if (controller != null) {
            controller.removeListener(this);
            miniPlayer.detach();
            controller = null;
        } else {
            miniPlayer.detach();
        }
        if (bindingRequested) {
            unbindService(serviceConnection);
            bindingRequested = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        BottomNavigation.release(this);
        searchGate.invalidate();
        searchSectionGeneration++;
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
        ArrayList<String> selected = new ArrayList<>();
        for (LibraryPlayTarget target : selection.targets()) selected.add(target.toJson());
        outState.putStringArrayList(STATE_SELECTION, selected);
        outState.putLong(STATE_QUEUE_ADD_OPERATION, queueAddOperationId);
        outState.putBoolean(STATE_QUEUE_ADD_SELECTION, queueAddFromSelection);
        super.onSaveInstanceState(outState);
    }

    void showTab(BottomNavigation.Tab tab) {
        if (tab != BottomNavigation.Tab.LIBRARY && tab != BottomNavigation.Tab.SEARCH) return;
        if (queueAddInFlight && tab != selectedTab) return;
        if (tab != selectedTab) clearSelectionWithoutRender();
        saveScrollPosition();
        if (tab == BottomNavigation.Tab.SEARCH && searchOrigin.active()) {
            restoreSearchOrigin();
        }
        selectedTab = tab;
        BottomNavigation.bind(this, selectedTab, findViewById(R.id.tab_content));
        searchContainer.setVisibility(
                tab == BottomNavigation.Tab.SEARCH ? View.VISIBLE : View.GONE
        );
        if (tab == BottomNavigation.Tab.LIBRARY) {
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (keyboard != null) {
                keyboard.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            }
            searchInput.clearFocus();
        }
        if (tab == BottomNavigation.Tab.LIBRARY && pendingSearchContainer != null) {
            LibraryItem destination = pendingSearchContainer;
            pendingSearchContainer = null;
            openSearchContainerNow(destination);
            return;
        }
        if (tab == BottomNavigation.Tab.SEARCH
                && connected()
                && !normalizedQuery().isEmpty()
                && !searchInitialized
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
        if (row.searchHeader != null) return;
        if (row.historyQuery != null) {
            selectHistoryQuery(row.historyQuery);
            return;
        }
        if (row.searchMore != null) {
            SearchSectionLoad state = searchSectionLoads.get(row.searchMore);
            loadSearchSection(
                    row.searchMore,
                    state != null
                            && state.failure
                            == RemoteClientController.LibraryFailure.PAGE_EXPIRED
            );
            return;
        }
        if (row.action != null) {
            openLocalAction(row);
            return;
        }
        LibraryItem item = row.item;
        if (item == null) return;
        if (selectedTab == BottomNavigation.Tab.SEARCH) {
            recordHistory(normalizedQuery());
        }
        if (selectedTab == BottomNavigation.Tab.SEARCH
                && SearchOriginState.supportsDestination(item)) {
            openSearchContainer(item);
            return;
        }
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

    private void openSearchContainer(LibraryItem item) {
        saveScrollPosition();
        if (pendingSearchContainer != null || searchOrigin.active()
                || !searchOrigin.begin(
                        normalizedQuery(),
                        searchResult,
                        searchFirstVisible,
                        searchTopOffset,
                        libraryStack.size()
                )) {
            return;
        }
        pendingSearchContainer = item;
        if (!BottomNavigation.open(this, BottomNavigation.Tab.LIBRARY)) {
            pendingSearchContainer = null;
            searchOrigin.clear();
        }
    }

    private void openSearchContainerNow(LibraryItem item) {
        if ("artist".equals(item.type)) {
            boolean membership = item.browseTarget != null
                    && LibraryBrowseTarget.TYPE_ARTIST_MEMBERSHIP.equals(
                            item.browseTarget.type
                    );
            pushNetwork(
                    displayTitle(item),
                    membership
                            ? LibraryRequest.artistMemberTracks(item.id)
                            : LibraryRequest.artistTracks(item.id),
                    item.representativeType(),
                    item.id
            );
        } else if ("album".equals(item.type)) {
            pushNetwork(
                    displayTitle(item),
                    LibraryRequest.albumTracks(item.id),
                    item.type,
                    item.id
            );
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
        push(new Level(title, requestWithStoredSort(request), null));
    }

    private void pushNetwork(
            String title,
            LibraryRequest request,
            String representativeType,
            long representativeId
    ) {
        push(new Level(
                title,
                requestWithStoredSort(request),
                null,
                representativeType,
                representativeId
        ));
    }

    private LibraryRequest requestWithStoredSort(LibraryRequest request) {
        if (request == null || !request.isTrackList() || librarySortStore == null) {
            return request;
        }
        LibrarySort stored = librarySortCapabilities.supportedOrDefault(
                librarySortStore.load(request.sortView)
        );
        return request.withSort(stored);
    }

    private void push(Level level) {
        clearSelectionWithoutRender();
        saveScrollPosition();
        currentLevel().loading = false; // Its outstanding append will be invalidated below.
        libraryStack.add(level);
        libraryRequestSerial++;
        render();
        if (!level.local()) loadLibraryPage(level, false);
    }

    private void showSortCriterionDialog() {
        if (!sortingVisible()) return;
        Level level = currentLevel();
        ArrayList<LibrarySort.Criterion> criteria = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        int checked = -1;
        for (LibrarySort.Criterion criterion : LibrarySort.Criterion.values()) {
            if (!librarySortCapabilities.supports(criterion)) continue;
            if (criterion == level.request.sort.criterion) checked = criteria.size();
            criteria.add(criterion);
            labels.add(getString(sortCriterionLabel(criterion)));
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.library_sort_dialog_title)
                .setSingleChoiceItems(
                        labels.toArray(new String[0]),
                        checked,
                        null
                )
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getListView().setOnItemClickListener(
                (parent, view, position, id) -> {
                    if (position >= 0 && position < criteria.size()) {
                        applySort(level, LibrarySort.forCriterion(criteria.get(position)), true);
                    }
                    dialog.dismiss();
                }
        ));
        dialog.show();
    }

    private void toggleSortDirection() {
        if (!sortingVisible()) return;
        Level level = currentLevel();
        LibrarySort selected = level.request.sort;
        if (selected.isPowerampOrder()) return;
        applySort(
                level,
                new LibrarySort(selected.criterion, selected.direction.opposite()),
                true
        );
    }

    private void applySort(Level level, LibrarySort selected, boolean persist) {
        if (level == null || level.local() || !level.request.isTrackList()) return;
        LibrarySort supported = librarySortCapabilities.supportedOrDefault(selected);
        if (!level.sortState.change(supported)) return;
        clearSelectionWithoutRender();
        if (persist && librarySortStore != null) {
            librarySortStore.save(level.request.sortView, supported);
        }
        libraryRequestSerial++;
        level.loading = false;
        level.failure = null;
        level.request = level.request.withSort(supported);
        level.pager.reset();
        level.firstVisible = 0;
        level.topOffset = 0;
        renderedSource = null;
        if (selectedTab == BottomNavigation.Tab.LIBRARY && level == currentLevel()) {
            render();
            listView.setSelection(0);
            if (connected()) loadLibraryPage(level, false);
        }
    }

    private boolean sortingVisible() {
        if (!selection.isEmpty() || selectedTab != BottomNavigation.Tab.LIBRARY
                || !librarySortCapabilities.hasSelectableSort()) return false;
        Level level = currentLevel();
        return !level.local() && level.request.isTrackList();
    }

    private void requestLibrarySortCapabilities() {
        if (controller == null || !connected()) return;
        int generation = controller.libraryConnectionGeneration();
        if (libraryCapabilitiesGeneration == generation) return;
        libraryCapabilitiesGeneration = generation;
        long serial = ++libraryCapabilitiesSerial;
        controller.requestLibraryCapabilities((resultGeneration, capabilities, failure) -> {
            if (!started || controller == null || serial != libraryCapabilitiesSerial
                    || resultGeneration != generation
                    || resultGeneration != controller.libraryConnectionGeneration()) return;
            if (failure != null || capabilities == null) return;
            librarySortCapabilities = capabilities;
            if (!capabilities.supportsQueueAdd()) clearSelectionWithoutRender();
            Level level = currentLevel();
            if (selectedTab == BottomNavigation.Tab.LIBRARY
                    && !level.local() && level.request.isTrackList()) {
                LibrarySort stored = librarySortCapabilities.supportedOrDefault(
                        librarySortStore.load(level.request.sortView)
                );
                applySort(level, stored, false);
            }
            render();
        });
    }

    private int sortCriterionLabel(LibrarySort.Criterion criterion) {
        switch (criterion) {
            case TITLE:
                return R.string.library_sort_title;
            case ALBUM:
                return R.string.library_sort_album;
            case ARTIST:
                return R.string.library_sort_artist;
            case DURATION:
                return R.string.library_sort_duration;
            case DATE_ADDED:
                return R.string.library_sort_date_added;
            case PLAY_COUNT:
                return R.string.library_sort_play_count;
            case DEFAULT:
            default:
                return R.string.library_sort_default;
        }
    }

    private int sortDirectionLabel(LibrarySort sort) {
        if (sort == null || sort.isPowerampOrder()) {
            return R.string.library_sort_direction_poweramp;
        }
        switch (sort.criterion) {
            case DURATION:
                return sort.direction == LibrarySort.Direction.ASCENDING
                        ? R.string.library_sort_shortest : R.string.library_sort_longest;
            case DATE_ADDED:
                return sort.direction == LibrarySort.Direction.ASCENDING
                        ? R.string.library_sort_oldest : R.string.library_sort_newest;
            case PLAY_COUNT:
                return sort.direction == LibrarySort.Direction.ASCENDING
                        ? R.string.library_sort_least_played
                        : R.string.library_sort_most_played;
            case TITLE:
            case ALBUM:
            case ARTIST:
            default:
                return sort.direction == LibrarySort.Direction.ASCENDING
                        ? R.string.library_sort_a_to_z : R.string.library_sort_z_to_a;
        }
    }

    private void navigateBack() {
        clearSelectionWithoutRender();
        if (selectedTab == BottomNavigation.Tab.LIBRARY && searchOrigin.active()) {
            SearchOriginState.Snapshot origin = searchOrigin.peek();
            if (origin != null && libraryStack.size() > origin.libraryDepth) {
                saveScrollPosition();
                if (!BottomNavigation.open(this, BottomNavigation.Tab.SEARCH)) {
                    return;
                }
                libraryRequestSerial++;
                while (libraryStack.size() > origin.libraryDepth) {
                    libraryStack.remove(libraryStack.size() - 1);
                }
                return;
            }
        }
        if (selectedTab == BottomNavigation.Tab.LIBRARY && libraryStack.size() > 1) {
            saveScrollPosition();
            libraryRequestSerial++;
            libraryStack.remove(libraryStack.size() - 1);
            render();
            return;
        }
        finish();
    }

    private void restoreSearchOrigin() {
        SearchOriginState.Snapshot origin = searchOrigin.consume();
        if (origin == null) return;
        while (libraryStack.size() > origin.libraryDepth) {
            libraryStack.remove(libraryStack.size() - 1);
        }
        restoringSearchText = true;
        searchInput.setText(origin.query);
        searchInput.setSelection(origin.query.length());
        restoringSearchText = false;
        searchResult = origin.result;
        resetSearchSections();
        searchInitialized = true;
        searchLoading = false;
        searchFailure = null;
        searchFirstVisible = origin.firstVisible;
        searchTopOffset = origin.topOffset;
        lastSuccessfulQueryKey = SearchHistoryPolicy.normalizationKey(origin.query);
        renderedSource = null;
    }

    private void scheduleSearch() {
        clearSelectionWithoutRender();
        cancelDebounce();
        searchGate.invalidate();
        resetSearchSections();
        searchResult = null;
        searchInitialized = false;
        searchFirstVisible = 0;
        searchTopOffset = 0;
        renderedSource = null;
        searchFailure = null;
        searchLoading = false;
        lastSuccessfulQueryKey = null;
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
            resetSearchSections();
            searchResult = null;
            searchInitialized = false;
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
        LibraryRequest requestedRequest = level.request;
        LibrarySortRequestState.Stamp sortStamp = level.sortState.begin();
        level.loading = true;
        level.failure = null;
        long serial = ++libraryRequestSerial;
        int generation = controller.libraryConnectionGeneration();
        render();
        controller.requestLibraryPage(requestedRequest, requestedToken, (resultGeneration, page, failure) -> {
            if (!started || serial != libraryRequestSerial || level != currentLevel()
                    || level.request != requestedRequest
                    || !level.sortState.accepts(sortStamp)
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

    private void loadSearchPage(boolean ignoredAppend) {
        if (controller == null || !connected()) {
            searchFailure = RemoteClientController.LibraryFailure.DISCONNECTED;
            render();
            return;
        }
        if (searchLoading) return;
        String query = normalizedQuery();
        if (query.isEmpty()) return;
        resetSearchSections();
        if (searchInitialized) {
            searchFirstVisible = 0;
            searchTopOffset = 0;
        }
        searchResult = null;
        searchInitialized = false;
        renderedSource = null;
        int generation = controller.libraryConnectionGeneration();
        SearchRequestGate.Request request = searchGate.begin(query, generation);
        searchLoading = true;
        searchFailure = null;
        render();
        CategorizedSearchRequest apiRequest;
        try {
            apiRequest = CategorizedSearchRequest.create(query);
        } catch (IllegalArgumentException exception) {
            searchLoading = false;
            searchFailure = RemoteClientController.LibraryFailure.SERVER_ERROR;
            render();
            return;
        }
        controller.requestCategorizedSearch(apiRequest, (resultGeneration, result, failure) -> {
            if (!started || controller == null || !searchGate.accepts(
                    request, normalizedQuery(), controller.libraryConnectionGeneration()
            ) || resultGeneration != generation) {
                return;
            }
            searchLoading = false;
            searchFailure = failure;
            if (failure == null && result != null) {
                searchResult = result;
                searchInitialized = true;
                lastSuccessfulQueryKey = SearchHistoryPolicy.normalizationKey(query);
            }
            if (selectedTab == BottomNavigation.Tab.SEARCH) render();
        });
    }

    private void loadSearchSection(
            CategorizedSearchResult.SectionType sectionType,
            boolean reload
    ) {
        if (controller == null || !connected() || searchResult == null) return;
        SearchSectionLoad state = searchSectionLoads.get(sectionType);
        if (state == null || state.loading) return;
        CategorizedSearchResult.Section section = searchResult.section(sectionType);
        String token = reload || section == null ? null : section.nextPageToken;
        if (!reload && token == null) return;
        String query = normalizedQuery();
        int connectionGeneration = controller.libraryConnectionGeneration();
        long sectionGeneration = searchSectionGeneration;
        long serial = ++state.serial;
        state.loading = true;
        state.failure = null;
        render();
        CategorizedSearchRequest request;
        try {
            request = CategorizedSearchRequest.section(query, sectionType, token);
        } catch (IllegalArgumentException exception) {
            state.loading = false;
            state.failure = RemoteClientController.LibraryFailure.SERVER_ERROR;
            render();
            return;
        }
        controller.requestCategorizedSearch(request, (resultGeneration, result, failure) -> {
            if (!started || controller == null || state.serial != serial
                    || searchSectionGeneration != sectionGeneration
                    || resultGeneration != connectionGeneration
                    || resultGeneration != controller.libraryConnectionGeneration()
                    || !query.equals(normalizedQuery())) return;
            state.loading = false;
            state.failure = failure;
            if (failure == null && result != null && searchResult != null) {
                try {
                    searchResult = searchResult.mergeSection(sectionType, result, reload);
                } catch (IllegalArgumentException exception) {
                    state.failure = RemoteClientController.LibraryFailure.SERVER_ERROR;
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

    private boolean canAddToQueue(LibraryItem item) {
        if (item == null || item.playTarget == null
                || !librarySortCapabilities.supportsQueueAdd()) return false;
        String type = item.playTarget.type;
        return "track".equals(type)
                || "playlist_entry".equals(type)
                || "queue_entry".equals(type);
    }

    private void showTrackActions(View anchor, LibraryItem item) {
        if (!canAddToQueue(item) || queueAddInFlight) return;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(R.string.add_to_queue);
        menu.setOnMenuItemClickListener(ignored -> {
            haptic(anchor);
            addTargetsToQueue(java.util.Collections.singletonList(item.playTarget), false);
            return true;
        });
        menu.show();
    }

    private void toggleSelection(LibraryItem item, View view) {
        if (!canAddToQueue(item) || queueAddInFlight) return;
        if (!selection.contains(item.playTarget)
                && selection.size() >= QueueAddRequest.MAX_ITEMS) {
            Toast.makeText(this, R.string.selection_limit, Toast.LENGTH_SHORT).show();
            return;
        }
        haptic(view);
        selection.toggle(item.playTarget);
        adapter.notifyDataSetChanged();
        render();
    }

    private void addSelectionToQueue() {
        if (selection.isEmpty()) return;
        addTargetsToQueue(selection.targets(), true);
    }

    private void addTargetsToQueue(List<LibraryPlayTarget> targets, boolean fromSelection) {
        if (queueAddInFlight || controller == null || !connected()
                || !librarySortCapabilities.supportsQueueAdd()) return;
        final QueueAddRequest request;
        try {
            request = new QueueAddRequest(targets);
        } catch (IllegalArgumentException exception) {
            Toast.makeText(this, R.string.add_to_queue_error, Toast.LENGTH_SHORT).show();
            return;
        }
        queueAddInFlight = true;
        queueAddFromSelection = fromSelection;
        queueAddOperationId = controller.addToQueue(request);
        if (queueAddOperationId <= 0L) {
            queueAddOperationId = 0L;
            queueAddFromSelection = false;
            synchronizeQueueAddOperation();
            Toast.makeText(this, R.string.add_to_queue_error, Toast.LENGTH_SHORT).show();
        }
        render();
    }

    private void clearSelection() {
        if (queueAddInFlight) return;
        clearSelectionWithoutRender();
        adapter.notifyDataSetChanged();
        render();
    }

    private void clearSelectionWithoutRender() {
        if (selection.isEmpty() || queueAddInFlight) return;
        selection.clear();
    }

    private void render() {
        Object source = selectedTab == BottomNavigation.Tab.SEARCH
                ? searchRenderSource : currentLevel();
        boolean changedSource = renderedSource != source;
        if (!changedSource) saveScrollPosition();
        BottomNavigation.bind(this, selectedTab, findViewById(R.id.tab_content));
        boolean connected = connected();
        boolean selecting = !selection.isEmpty();
        findViewById(R.id.library_back_button).setVisibility(
                selecting || selectedTab == BottomNavigation.Tab.LIBRARY
                        && libraryStack.size() > 1
                        ? View.VISIBLE : View.INVISIBLE
        );
        titleView.setVisibility(selecting ? View.GONE : View.VISIBLE);
        selectionCount.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectionAddQueue.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectionClose.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectionCount.setText(getString(R.string.selection_count, selection.size()));
        boolean mutationEnabled = selecting && connected && !queueAddInFlight;
        selectionAddQueue.setEnabled(mutationEnabled);
        selectionAddQueue.setAlpha(mutationEnabled ? 1f : 0.38f);
        selectionClose.setEnabled(!queueAddInFlight);
        selectionClose.setAlpha(queueAddInFlight ? 0.38f : 1f);
        titleView.setText(selectedTab == BottomNavigation.Tab.SEARCH
                ? getString(R.string.nav_search) : currentLevel().title);
        updateSortControls();
        searchContainer.setVisibility(
                selectedTab == BottomNavigation.Tab.SEARCH ? View.VISIBLE : View.GONE
        );
        searchInput.setEnabled(!queueAddInFlight);
        searchClear.setEnabled(!queueAddInFlight);
        progress.setVisibility(View.GONE);
        statusMessage.setVisibility(View.GONE);
        actionButton.setVisibility(View.GONE);
        actionButton.setOnClickListener(null);

        if (selectedTab == BottomNavigation.Tab.SEARCH) renderSearch();
        else renderLibrary();
        if (!connected && !showingHistory()) {
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

    private void updateSortControls() {
        boolean visible = sortingVisible();
        sortCriterion.setVisibility(visible ? View.VISIBLE : View.GONE);
        sortDirection.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;
        LibrarySort sort = currentLevel().request.sort;
        String criterion = getString(sortCriterionLabel(sort.criterion));
        String criterionDescription = getString(R.string.library_sort_by, criterion);
        sortCriterion.setContentDescription(criterionDescription);
        sortCriterion.setTooltipText(criterionDescription);

        boolean directional = !sort.isPowerampOrder();
        sortDirection.setEnabled(directional);
        sortDirection.setAlpha(directional ? 1f : 0.38f);
        sortDirection.setImageResource(
                sort.direction == LibrarySort.Direction.DESCENDING
                        ? R.drawable.ic_sort_descending : R.drawable.ic_sort_ascending
        );
        String direction = getString(sortDirectionLabel(sort));
        String directionDescription = directional
                ? getString(R.string.library_sort_direction, direction)
                : direction;
        sortDirection.setContentDescription(directionDescription);
        sortDirection.setTooltipText(directionDescription);
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
                () -> {
                    boolean reload = level.failure
                            == RemoteClientController.LibraryFailure.PAGE_EXPIRED;
                    if (reload) clearSelectionWithoutRender();
                    loadLibraryPage(level, level.pager.initialized() && !reload);
                }
        );
    }

    private void renderSearch() {
        String query = normalizedQuery();
        if (query.isEmpty()) {
            List<BrowseRow> historyRows = new ArrayList<>();
            if (showingHistory() && !searchHistory.isEmpty()) {
                historyRows.add(BrowseRow.historyHeader());
                for (String entry : searchHistory) historyRows.add(BrowseRow.history(entry));
            }
            adapter.setRows(historyRows);
            listView.setVisibility(historyRows.isEmpty() ? View.GONE : View.VISIBLE);
            if (historyRows.isEmpty()) showMessage(R.string.library_search_prompt);
            return;
        }
        List<BrowseRow> rows = new ArrayList<>();
        for (SearchPresentationPolicy.Row row : SearchPresentationPolicy.rows(searchResult)) {
            if (row.isHeader()) rows.add(BrowseRow.searchHeader(row.header));
            else if (row.more != null) rows.add(BrowseRow.searchMore(row.more));
            else rows.add(BrowseRow.item(row.item));
        }
        adapter.setRows(rows);
        listView.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        if (searchFailure != null) {
            showMessage(failureMessage(searchFailure));
            if (searchFailure != RemoteClientController.LibraryFailure.UNSUPPORTED) {
                showAction(R.string.library_retry, () -> loadSearchPage(false));
            }
            return;
        }
        if (searchLoading) {
            progress.setVisibility(View.VISIBLE);
            if (rows.isEmpty()) showMessage(R.string.library_loading);
        } else if (searchInitialized && rows.isEmpty()) {
            showMessage(R.string.library_search_empty);
        }
    }

    private void renderNetworkPage(
            LibraryPager pager,
            boolean loading,
            RemoteClientController.LibraryFailure failure,
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
            showMessage(R.string.library_empty);
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
        Object source = selectedTab == BottomNavigation.Tab.SEARCH
                ? searchRenderSource : currentLevel();
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
        resetSearchSections();
        searchLoading = false;
        for (Level level : libraryStack) level.loading = false;
        if (serverChanged) {
            queueAddOperationId = 0L;
            queueAddFromSelection = false;
            queueAddInFlight = false;
            clearSelectionWithoutRender();
            librarySortCapabilities = LibrarySortCapabilities.defaultOnly();
            libraryCapabilitiesGeneration = Integer.MIN_VALUE;
            libraryCapabilitiesSerial++;
            libraryStack.clear();
            libraryStack.add(libraryRoot());
            searchResult = null;
            searchInitialized = false;
            searchFailure = null;
            searchOrigin.clear();
            pendingSearchContainer = null;
        }
    }

    private void cancelDebounce() {
        if (debounceRunnable != null) {
            handler.removeCallbacks(debounceRunnable);
            debounceRunnable = null;
        }
    }

    private void resetSearchSections() {
        searchSectionGeneration++;
        for (SearchSectionLoad state : searchSectionLoads.values()) {
            state.serial++;
            state.loading = false;
            state.failure = null;
        }
    }

    private void clearSearchInput() {
        clearSelectionWithoutRender();
        cancelDebounce();
        searchGate.invalidate();
        resetSearchSections();
        restoringSearchText = true;
        searchInput.setText("");
        restoringSearchText = false;
        searchResult = null;
        searchInitialized = false;
        searchLoading = false;
        searchFailure = null;
        lastSuccessfulQueryKey = null;
        searchFirstVisible = 0;
        searchTopOffset = 0;
        renderedSource = null;
        searchInput.requestFocus();
        searchInput.post(() -> {
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.showSoftInput(
                    searchInput, InputMethodManager.SHOW_IMPLICIT
            );
        });
        render();
    }

    private boolean showingHistory() {
        return selectedTab == BottomNavigation.Tab.SEARCH
                && normalizedQuery().isEmpty()
                && searchInput.hasFocus()
                && imeVisible;
    }

    private void saveSuccessfulQueryOnImeClose() {
        String query = normalizedQuery();
        if (searchInitialized && searchFailure == null && !query.isEmpty()
                && SearchHistoryPolicy.normalizationKey(query).equals(
                        lastSuccessfulQueryKey
                )) {
            recordHistory(query);
        }
    }

    private void recordHistory(String query) {
        if (query == null || query.trim().isEmpty() || searchHistoryStore == null) return;
        if (searchHistoryStore.record(query)) searchHistory = searchHistoryStore.load();
    }

    private void selectHistoryQuery(String query) {
        recordHistory(query);
        restoringSearchText = true;
        searchInput.setText(query);
        searchInput.setSelection(query.length());
        restoringSearchText = false;
        searchInput.requestFocus();
        scheduleSearch();
        runSearchNow();
    }

    private void removeHistoryQuery(View view, String query) {
        haptic(view);
        if (searchHistoryStore.remove(query)) searchHistory = searchHistoryStore.load();
        render();
    }

    private String displayTitle(LibraryItem item) {
        return item.title == null || item.title.trim().isEmpty()
                ? getString(R.string.library_unknown_item) : item.title;
    }

    private String formatDateAdded(long epochSeconds) {
        if (epochSeconds < 0L || epochSeconds > Long.MAX_VALUE / 1_000L) return null;
        return dateAddedFormat.format(new Date(epochSeconds * 1_000L));
    }

    private int searchHeaderLabel(CategorizedSearchResult.SectionType section) {
        switch (section) {
            case TRACKS:
                return R.string.search_section_tracks;
            case ARTISTS:
                return R.string.search_section_artists;
            case ALBUMS:
            default:
                return R.string.search_section_albums;
        }
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
        if (connected()) requestLibrarySortCapabilities();
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
                if (!normalizedQuery().isEmpty() && !searchInitialized
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
                && !searchInitialized && !searchLoading && searchFailure == null) {
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
    @Override
    public void onStateChanged(RemoteState state, long receivedRealtimeMilliseconds) {
        adapter.setConfirmedState(state);
    }
    @Override public void onArtworkChanged(Bitmap artwork) { }
    @Override public void onCommandError(boolean authenticationError) { }
    @Override public void onPlaybackSnapshot(PlaybackUiSnapshot snapshot) { }

    @Override
    public void onQueueAddCompleted(
            long operationId,
            int connectionGeneration,
            QueueAddResult result,
            RemoteClientController.LibraryFailure failure
    ) {
        queueAddInFlight = false;
        if (operationId != queueAddOperationId) {
            render();
            return;
        }
        boolean fromSelection = queueAddFromSelection;
        queueAddOperationId = 0L;
        queueAddFromSelection = false;
        if (!started || controller == null) {
            render();
            return;
        }
        if (failure != null || result == null) {
            Toast.makeText(this, R.string.add_to_queue_error, Toast.LENGTH_SHORT).show();
        } else if (result.complete) {
            if (fromSelection) selection.clear();
            Toast.makeText(this, R.string.add_to_queue_success, Toast.LENGTH_SHORT).show();
        } else {
            if (fromSelection && result.addedCount > 0) {
                selection.removeFirst(result.addedCount);
            }
            Toast.makeText(
                    this,
                    result.addedCount > 0
                            ? getString(
                                    R.string.add_to_queue_result,
                                    result.addedCount,
                                    result.requestedCount
                            )
                            : getString(R.string.add_to_queue_error),
                    Toast.LENGTH_SHORT
            ).show();
        }
        adapter.notifyDataSetChanged();
        render();
    }

    private void synchronizeQueueAddOperation() {
        if (controller == null) {
            queueAddInFlight = false;
            return;
        }
        PhoneConnectionService.QueueAddOperation operation =
                controller.queueAddOperation();
        if (queueAddOperationId != 0L
                && (operation == null || operation.id != queueAddOperationId)) {
            queueAddOperationId = 0L;
            queueAddFromSelection = false;
        }
        queueAddInFlight = operation != null && operation.inFlight;
    }

    private static void haptic(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private final class BrowseAdapter extends BaseAdapter {
        private List<BrowseRow> rows = new ArrayList<>();
        private final CurrentTrackMatcher.IndicatorState currentTrack =
                new CurrentTrackMatcher.IndicatorState();

        void setConfirmedState(RemoteState state) {
            currentTrack.update(state);
            int first = listView.getFirstVisiblePosition();
            for (int childIndex = 0; childIndex < listView.getChildCount(); childIndex++) {
                View child = listView.getChildAt(childIndex);
                Object tag = child.getTag();
                if (!(tag instanceof Holder)) continue;
                renderCurrentIndicator(
                        (Holder) tag,
                        getItem(first + childIndex)
                );
            }
        }

        void setRows(List<BrowseRow> rows) {
            if (this.rows.size() == rows.size()) {
                boolean same = true;
                for (int i = 0; i < rows.size(); i++) {
                    BrowseRow old = this.rows.get(i);
                    BrowseRow next = rows.get(i);
                    if (old.item != next.item || old.action != next.action
                            || old.labelResource != next.labelResource
                            || old.folderId != next.folderId
                            || old.searchHeader != next.searchHeader
                            || old.searchMore != next.searchMore
                            || old.historyHeader != next.historyHeader
                            || !sameText(old.historyQuery, next.historyQuery)) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    // Section continuation changes its local progress/retry state without
                    // replacing the immutable presentation rows.
                    for (BrowseRow row : rows) {
                        if (row.searchMore != null) {
                            notifyDataSetChanged();
                            break;
                        }
                    }
                    return;
                }
            }
            this.rows = rows;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return rows.size(); }
        @Override public BrowseRow getItem(int position) {
            return position < 0 || position >= rows.size() ? null : rows.get(position);
        }
        @Override public int getViewTypeCount() { return 4; }
        @Override public int getItemViewType(int position) {
            BrowseRow row = getItem(position);
            if (row == null) return 0;
            if (row.searchHeader != null || row.historyHeader) return 1;
            if (row.searchMore != null) return 2;
            if (row.historyQuery != null) return 3;
            return 0;
        }
        @Override public boolean isEnabled(int position) {
            BrowseRow row = getItem(position);
            if (row == null || row.searchHeader != null || row.historyHeader) return false;
            if (row.searchMore == null) return true;
            SearchSectionLoad state = searchSectionLoads.get(row.searchMore);
            return state == null || !state.loading;
        }
        @Override public long getItemId(int position) {
            BrowseRow row = getItem(position);
            if (row != null && row.searchHeader != null) {
                return Long.MIN_VALUE + row.searchHeader.ordinal();
            }
            if (row != null && row.historyHeader) return Long.MIN_VALUE + 4L;
            if (row != null && row.searchMore != null) {
                return Long.MIN_VALUE + 8L + row.searchMore.ordinal();
            }
            if (row != null && row.historyQuery != null) {
                return SearchHistoryPolicy.normalizationKey(row.historyQuery).hashCode();
            }
            return row == null || row.item == null ? position : row.item.id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            BrowseRow row = getItem(position);
            if (row != null && (row.searchHeader != null || row.historyHeader)) {
                TextView header;
                if (convertView == null) {
                    convertView = LayoutInflater.from(LibrarySearchActivity.this)
                            .inflate(R.layout.search_section_header, parent, false);
                }
                header = (TextView) convertView;
                header.setText(row.historyHeader
                        ? R.string.search_history_title
                        : searchHeaderLabel(row.searchHeader));
                resetNonInteractive(convertView);
                return convertView;
            }
            if (row != null && row.searchMore != null) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(LibrarySearchActivity.this)
                            .inflate(R.layout.search_section_more, parent, false);
                }
                ProgressBar localProgress = convertView.findViewById(
                        R.id.search_section_more_progress
                );
                TextView label = convertView.findViewById(R.id.search_section_more_label);
                SearchSectionLoad state = searchSectionLoads.get(row.searchMore);
                boolean loading = state != null && state.loading;
                localProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
                if (loading) label.setText(R.string.library_loading);
                else if (state != null
                        && state.failure == RemoteClientController.LibraryFailure.PAGE_EXPIRED) {
                    label.setText(R.string.search_section_reload);
                } else if (state != null && state.failure != null) {
                    label.setText(R.string.search_section_retry);
                } else {
                    label.setText(R.string.search_show_more);
                }
                boolean enabled = !loading;
                convertView.setEnabled(enabled);
                convertView.setClickable(enabled);
                convertView.setLongClickable(false);
                convertView.setActivated(false);
                convertView.setSelected(false);
                convertView.setOnLongClickListener(null);
                convertView.setOnClickListener(enabled ? view -> {
                    haptic(view);
                    openRow(row);
                } : null);
                return convertView;
            }
            if (row != null && row.historyQuery != null) {
                if (convertView == null) {
                    convertView = LayoutInflater.from(LibrarySearchActivity.this)
                            .inflate(R.layout.search_history_item, parent, false);
                }
                TextView query = convertView.findViewById(R.id.search_history_query);
                ImageButton remove = convertView.findViewById(R.id.search_history_remove);
                query.setText(row.historyQuery);
                convertView.setEnabled(true);
                convertView.setClickable(true);
                convertView.setLongClickable(false);
                convertView.setActivated(false);
                convertView.setSelected(false);
                convertView.setOnLongClickListener(null);
                convertView.setOnClickListener(view -> {
                    haptic(view);
                    selectHistoryQuery(row.historyQuery);
                });
                remove.setEnabled(true);
                remove.setClickable(true);
                remove.setLongClickable(false);
                remove.setActivated(false);
                remove.setSelected(false);
                remove.setContentDescription(getString(R.string.search_history_remove));
                remove.setOnLongClickListener(null);
                remove.setOnClickListener(view -> removeHistoryQuery(view, row.historyQuery));
                return convertView;
            }
            Holder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(LibrarySearchActivity.this)
                        .inflate(R.layout.library_list_item, parent, false);
                holder = new Holder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (Holder) convertView.getTag();
            }
            bindPrimaryInteraction(holder, row);
            renderCurrentIndicator(holder, row);
            if (row == null) {
                resetMenu(holder.menu);
                holder.root.setActivated(false);
                holder.artworkGate.bind(null);
                holder.artwork.setVisibility(View.INVISIBLE);
                holder.title.setText("");
                setOptional(holder.subtitle, null);
                setOptional(holder.detail, null);
                return convertView;
            }
            if (row.item == null) {
                holder.root.setActivated(false);
                resetMenu(holder.menu);
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
            boolean track = "track".equals(item.type)
                    || "playlist_entry".equals(item.type)
                    || "queue_entry".equals(item.type);
            holder.root.setActivated(track && selection.contains(item.playTarget));
            boolean menuVisible = track && selection.isEmpty()
                    && canAddToQueue(item) && !queueAddInFlight;
            holder.menu.setVisibility(menuVisible ? View.VISIBLE : View.GONE);
            holder.menu.setEnabled(menuVisible);
            holder.menu.setClickable(menuVisible);
            holder.menu.setLongClickable(false);
            holder.menu.setActivated(false);
            holder.menu.setSelected(false);
            holder.menu.setContentDescription(menuVisible
                    ? getString(R.string.track_actions_description) : null);
            holder.menu.setOnLongClickListener(null);
            holder.menu.setOnClickListener(menuVisible
                    ? view -> showTrackActions(view, item) : null);
            ArrayList<String> detail = new ArrayList<>(4);
            if (item.trackCount != null) {
                detail.add(getResources().getQuantityString(
                        R.plurals.library_track_count, item.trackCount, item.trackCount
                ));
            }
            if (item.durationMilliseconds != null) {
                long seconds = item.durationMilliseconds / 1_000L;
                detail.add(TimeFormatter.formatSeconds((int) Math.min(seconds, Integer.MAX_VALUE)));
            }
            if (track && item.dateAddedEpochSeconds != null) {
                String date = formatDateAdded(item.dateAddedEpochSeconds);
                if (date != null) detail.add(getString(R.string.library_date_added, date));
            }
            if (track && item.playCount != null) {
                int quantity = (int) Math.min(item.playCount, Integer.MAX_VALUE);
                detail.add(getResources().getQuantityString(
                        R.plurals.library_play_count, quantity, item.playCount
                ));
            }
            setOptional(holder.detail, join(detail));
            String representativeType = item.representativeType();
            boolean representative = RepresentativeArtworkKey.isSupportedType(
                    representativeType
            );
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
                        ? null : controller.representativeArtworkKey(
                                representativeType, item.id
                        );
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

        private void bindPrimaryInteraction(Holder holder, BrowseRow row) {
            boolean available = row != null;
            boolean longEnabled = available && canAddToQueue(row.item)
                    && !queueAddInFlight;
            holder.root.setEnabled(available);
            holder.root.setClickable(available);
            holder.root.setLongClickable(longEnabled);
            holder.root.setSelected(false);
            holder.root.setContentDescription(null);
            holder.root.setOnClickListener(available ? view -> {
                TrackRowInteractionPolicy.Action action =
                        TrackRowInteractionPolicy.tap(
                                !selection.isEmpty(),
                                canAddToQueue(row.item),
                                !queueAddInFlight
                        );
                if (action == TrackRowInteractionPolicy.Action.TOGGLE_SELECTION) {
                    toggleSelection(row.item, view);
                } else if (action == TrackRowInteractionPolicy.Action.OPEN) {
                    haptic(view);
                    openRow(row);
                }
            } : null);
            holder.root.setOnLongClickListener(longEnabled ? view -> {
                TrackRowInteractionPolicy.Action action =
                        TrackRowInteractionPolicy.longPress(
                                canAddToQueue(row.item),
                                !queueAddInFlight
                        );
                if (action != TrackRowInteractionPolicy.Action.TOGGLE_SELECTION) {
                    return false;
                }
                toggleSelection(row.item, view);
                return true;
            } : null);
        }

        private void resetMenu(ImageButton menu) {
            menu.setVisibility(View.GONE);
            menu.setEnabled(false);
            menu.setClickable(false);
            menu.setLongClickable(false);
            menu.setActivated(false);
            menu.setSelected(false);
            menu.setContentDescription(null);
            menu.setOnClickListener(null);
            menu.setOnLongClickListener(null);
        }

        private void resetNonInteractive(View view) {
            view.setEnabled(false);
            view.setClickable(false);
            view.setLongClickable(false);
            view.setActivated(false);
            view.setSelected(false);
            view.setOnClickListener(null);
            view.setOnLongClickListener(null);
        }

        private boolean sameText(String first, String second) {
            return first == null ? second == null : first.equals(second);
        }

        private void renderCurrentIndicator(Holder holder, BrowseRow row) {
            boolean current = row != null && row.item != null
                    && currentTrack.matches(row.item);
            holder.current.setVisibility(current ? View.VISIBLE : View.INVISIBLE);
            holder.current.setContentDescription(current
                    ? getString(R.string.library_current_track) : null);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                holder.root.setStateDescription(current
                        ? getString(R.string.library_current_track) : null);
            }
        }
    }

    private static final class Holder {
        final View root;
        final ImageView artwork;
        final ImageView current;
        final ImageButton menu;
        final TextView title;
        final TextView subtitle;
        final TextView detail;
        final LibraryArtworkBindingGate artworkGate = new LibraryArtworkBindingGate();

        Holder(View view) {
            root = view;
            artwork = view.findViewById(R.id.library_item_artwork);
            current = view.findViewById(R.id.library_item_current);
            menu = view.findViewById(R.id.library_item_menu);
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
