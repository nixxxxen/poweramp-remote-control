package dev.powerampremote.phone;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import java.util.ArrayList;
import java.util.List;

/** Read-only Queue presentation backed only by the existing Phone connection service. */
public final class QueueActivity extends LocaleAwareActivity
        implements PhoneConnectionService.Listener {
    private static final String STATE_SELECTION = "queue_track_selection";
    private static final String STATE_QUEUE_ADD_OPERATION = "queue_add_operation";
    private static final String STATE_QUEUE_ADD_SELECTION = "queue_add_selection";
    private final LibraryPager pager = new LibraryPager();
    private final QueueRequestGate requestGate = new QueueRequestGate();

    private ListView listView;
    private ProgressBar progress;
    private TextView statusMessage;
    private Button actionButton;
    private ImageButton reloadButton;
    private TextView titleView;
    private TextView selectionCount;
    private ImageButton selectionAdd;
    private ImageButton selectionClose;
    private QueueAdapter adapter;
    private MiniPlayerController miniPlayer;
    private PhoneConnectionService.LocalBinder controller;
    private RemoteClientController.Status connectionStatus =
            RemoteClientController.Status.SEARCHING;
    private RemoteClientController.LibraryFailure failure;
    private boolean loading;
    private boolean started;
    private boolean bindingRequested;
    private int knownConnectionGeneration = Integer.MIN_VALUE;
    private String knownServerId;
    private int artworkBindingLifecycle;
    private int firstVisible;
    private int topOffset;
    private long playRequestSerial;
    private final TrackSelection selection = new TrackSelection();
    private LibrarySortCapabilities capabilities = LibrarySortCapabilities.defaultOnly();
    private int capabilitiesGeneration = Integer.MIN_VALUE;
    private long capabilitiesSerial;
    private long queueAddOperationId;
    private boolean queueAddFromSelection;
    private boolean queueAddInFlight;
    private long loadedQueueContentGeneration = -1L;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!(service instanceof PhoneConnectionService.LocalBinder)) {
                connectionStatus = RemoteClientController.Status.ERROR;
                render();
                return;
            }
            controller = (PhoneConnectionService.LocalBinder) service;
            adapter.setConfirmedState(null);
            controller.addListener(QueueActivity.this);
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
            requestGate.invalidate();
            loading = false;
            playRequestSerial++;
            queueAddInFlight = false;
            render();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            onServiceDisconnected(name);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SafeDrawingInsets.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_queue);
        SafeDrawingInsets.apply(findViewById(R.id.queue_screen_root));
        listView = findViewById(R.id.queue_list);
        progress = findViewById(R.id.queue_progress);
        statusMessage = findViewById(R.id.queue_status_message);
        actionButton = findViewById(R.id.queue_action_button);
        reloadButton = findViewById(R.id.queue_reload_button);
        titleView = findViewById(R.id.queue_title);
        selectionCount = findViewById(R.id.queue_selection_count);
        selectionAdd = findViewById(R.id.queue_selection_add);
        selectionClose = findViewById(R.id.queue_selection_close);
        miniPlayer = new MiniPlayerController(this);
        adapter = new QueueAdapter();
        listView.setAdapter(adapter);
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

        findViewById(R.id.queue_back_button).setOnClickListener(view -> {
            haptic(view);
            if (!selection.isEmpty()) clearSelection();
            else finish();
        });
        reloadButton.setOnClickListener(view -> {
            haptic(view);
            reload(true);
        });
        selectionAdd.setOnClickListener(view -> {
            haptic(view);
            if (!selection.isEmpty()) addToQueue(selection.targets(), true);
        });
        selectionClose.setOnClickListener(view -> {
            haptic(view);
            clearSelection();
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
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!selection.isEmpty()) clearSelection();
                else finish();
            }
        });
        BottomNavigation.bindAuxiliary(
                this, BottomNavigation.Tab.PLAYER, findViewById(R.id.queue_content)
        );

        try {
            PhoneConnectionService.start(this);
        } catch (RuntimeException ignored) {
            connectionStatus = RemoteClientController.Status.ERROR;
        }
        render();
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
        if (!bindingRequested) {
            connectionStatus = RemoteClientController.Status.ERROR;
            render();
        }
    }

    @Override
    protected void onStop() {
        saveScrollPosition();
        BottomNavigation.cancel(this);
        started = false;
        artworkBindingLifecycle++;
        requestGate.invalidate();
        loading = false;
        playRequestSerial++;
        capabilitiesSerial++;
        capabilitiesGeneration = Integer.MIN_VALUE;
        queueAddInFlight = false;
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
        requestGate.invalidate();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        ArrayList<String> selected = new ArrayList<>();
        for (LibraryPlayTarget target : selection.targets()) selected.add(target.toJson());
        outState.putStringArrayList(STATE_SELECTION, selected);
        outState.putLong(STATE_QUEUE_ADD_OPERATION, queueAddOperationId);
        outState.putBoolean(STATE_QUEUE_ADD_SELECTION, queueAddFromSelection);
        super.onSaveInstanceState(outState);
    }

    private void loadMoreIfAvailable() {
        if (connected() && !loading && failure == null && pager.canLoadMore()) {
            saveScrollPosition();
            loadPage(true);
        }
    }

    private void loadPage(boolean append) {
        PhoneConnectionService.LocalBinder active = controller;
        if (active == null || !connected()) {
            failure = RemoteClientController.LibraryFailure.DISCONNECTED;
            render();
            return;
        }
        if (loading) return;
        if (!append) pager.reset();
        String requestedToken = append ? pager.nextPageToken() : null;
        int connectionGeneration = active.libraryConnectionGeneration();
        QueueRequestGate.Request request = requestGate.begin(
                connectionGeneration, requestedToken
        );
        loading = true;
        failure = null;
        render();
        active.requestLibraryPage(
                LibraryRequest.queue(),
                requestedToken,
                (resultGeneration, page, resultFailure) -> {
                    if (!started || controller == null
                            || resultGeneration != connectionGeneration
                            || resultGeneration != controller.libraryConnectionGeneration()
                            || !requestGate.accepts(
                                    request,
                                    controller.libraryConnectionGeneration(),
                                    requestedToken
                            )) {
                        return;
                    }
                    loading = false;
                    failure = resultFailure;
                    if (failure == null && page != null) {
                        try {
                            if (!"queue".equals(page.category)) {
                                throw new IllegalArgumentException("Unexpected Queue category");
                            }
                            pager.accept(requestedToken, page);
                            if (!append && controller != null) {
                                loadedQueueContentGeneration =
                                        controller.queueContentGeneration();
                            }
                        } catch (IllegalArgumentException exception) {
                            failure = RemoteClientController.LibraryFailure.SERVER_ERROR;
                        }
                    }
                    render();
                }
        );
    }

    private void reload(boolean clearSelection) {
        if (clearSelection) clearSelectionWithoutRender();
        requestGate.invalidate();
        loading = false;
        failure = null;
        pager.reset();
        loadedQueueContentGeneration = -1L;
        firstVisible = 0;
        topOffset = 0;
        artworkBindingLifecycle++;
        listView.setSelection(0);
        adapter.setItems(pager.items());
        if (connected()) loadPage(false);
        else render();
    }

    private void play(LibraryPlayTarget target) {
        if (controller == null || !connected()) {
            Toast.makeText(this, R.string.queue_disconnected, Toast.LENGTH_SHORT).show();
            return;
        }
        long serial = ++playRequestSerial;
        int generation = controller.libraryConnectionGeneration();
        controller.playLibraryTarget(target, (resultGeneration, resultFailure) -> {
            if (!started || controller == null || serial != playRequestSerial
                    || resultGeneration != generation
                    || resultGeneration != controller.libraryConnectionGeneration()) return;
            if (resultFailure != null) {
                Toast.makeText(this, messageForFailure(resultFailure), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private boolean canAdd(LibraryItem item) {
        return item != null && item.playTarget != null
                && "queue_entry".equals(item.playTarget.type)
                && capabilities.supportsQueueAdd();
    }

    private void showTrackActions(View anchor, LibraryItem item) {
        if (!canAdd(item) || queueAddInFlight) return;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(R.string.add_to_queue);
        menu.setOnMenuItemClickListener(ignored -> {
            haptic(anchor);
            addToQueue(java.util.Collections.singletonList(item.playTarget), false);
            return true;
        });
        menu.show();
    }

    private void toggleSelection(LibraryItem item, View view) {
        if (!canAdd(item) || queueAddInFlight) return;
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

    private void addToQueue(List<LibraryPlayTarget> targets, boolean fromSelection) {
        if (queueAddInFlight || controller == null || !connected()
                || !capabilities.supportsQueueAdd()) return;
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
        clearSelectionWithoutRender();
        adapter.notifyDataSetChanged();
        render();
    }

    private void clearSelectionWithoutRender() {
        if (selection.isEmpty() || queueAddInFlight) return;
        selection.clear();
    }

    private void requestCapabilities() {
        if (controller == null || !connected()) return;
        int generation = controller.libraryConnectionGeneration();
        if (capabilitiesGeneration == generation) return;
        capabilitiesGeneration = generation;
        long serial = ++capabilitiesSerial;
        controller.requestLibraryCapabilities((resultGeneration, result, resultFailure) -> {
            if (!started || controller == null || serial != capabilitiesSerial
                    || resultGeneration != generation
                    || resultGeneration != controller.libraryConnectionGeneration()) return;
            if (resultFailure == null && result != null) {
                capabilities = result;
                if (!capabilities.supportsQueueAdd()) selection.clear();
                adapter.notifyDataSetChanged();
                render();
            }
        });
    }

    private void render() {
        BottomNavigation.bindAuxiliary(
                this, BottomNavigation.Tab.PLAYER, findViewById(R.id.queue_content)
        );
        adapter.setItems(pager.items());
        boolean selecting = !selection.isEmpty();
        titleView.setVisibility(selecting ? View.GONE : View.VISIBLE);
        reloadButton.setVisibility(selecting ? View.GONE : View.VISIBLE);
        selectionCount.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectionAdd.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectionClose.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectionCount.setText(getString(R.string.selection_count, selection.size()));
        boolean mutationEnabled = selecting && connected() && !queueAddInFlight;
        selectionAdd.setEnabled(mutationEnabled);
        selectionAdd.setAlpha(mutationEnabled ? 1f : 0.38f);
        selectionClose.setEnabled(!queueAddInFlight);
        selectionClose.setAlpha(queueAddInFlight ? 0.38f : 1f);
        QueuePresentationPolicy.Model model = QueuePresentationPolicy.resolve(
                connected(),
                pager.initialized(),
                loading,
                pager.items().size(),
                pager.canLoadMore(),
                pager.truncated(),
                failure
        );
        listView.setVisibility(model.rowsVisible ? View.VISIBLE : View.GONE);
        progress.setVisibility(model.progressVisible ? View.VISIBLE : View.GONE);
        statusMessage.setVisibility(View.GONE);
        actionButton.setVisibility(View.GONE);
        actionButton.setOnClickListener(null);
        reloadButton.setEnabled(connected());
        reloadButton.setAlpha(connected() ? 1f : 0.38f);

        int message = messageResource(model.message);
        if (message != 0) {
            statusMessage.setText(message);
            statusMessage.setVisibility(View.VISIBLE);
        }
        if (model.action != QueuePresentationPolicy.Action.NONE) {
            actionButton.setText(actionResource(model.action));
            actionButton.setVisibility(View.VISIBLE);
            actionButton.setOnClickListener(view -> {
                haptic(view);
                perform(model.action);
            });
        }
    }

    private void perform(QueuePresentationPolicy.Action action) {
        switch (action) {
            case LOAD_MORE:
                loadPage(true);
                break;
            case RELOAD:
                reload(true);
                break;
            case RETRY:
                loadPage(pager.initialized());
                break;
            case NONE:
            default:
                break;
        }
    }

    private int messageResource(QueuePresentationPolicy.Message message) {
        switch (message) {
            case LOADING:
                return R.string.queue_loading;
            case EMPTY:
                return R.string.queue_empty;
            case DISCONNECTED:
                return R.string.queue_disconnected;
            case PERMISSION_REQUIRED:
                return R.string.queue_permission_required;
            case UNSUPPORTED:
                return R.string.queue_unsupported;
            case PROVIDER_UNAVAILABLE:
                return R.string.queue_provider_unavailable;
            case PAGE_EXPIRED:
                return R.string.queue_page_expired;
            case ERROR:
                return R.string.queue_error;
            case INCOMPLETE:
                return R.string.queue_incomplete;
            case NONE:
            default:
                return 0;
        }
    }

    private int actionResource(QueuePresentationPolicy.Action action) {
        switch (action) {
            case RELOAD:
                return R.string.queue_reload;
            case LOAD_MORE:
                return R.string.queue_more;
            case RETRY:
            default:
                return R.string.queue_retry;
        }
    }

    private int messageForFailure(RemoteClientController.LibraryFailure resultFailure) {
        if (resultFailure == null) return R.string.queue_play_error;
        switch (resultFailure) {
            case DISCONNECTED:
                return R.string.queue_disconnected;
            case PERMISSION_REQUIRED:
                return R.string.queue_permission_required;
            case UNSUPPORTED:
                return R.string.queue_unsupported;
            case PROVIDER_UNAVAILABLE:
                return R.string.queue_provider_unavailable;
            case PAGE_EXPIRED:
                return R.string.queue_page_expired;
            case AUTHENTICATION:
            case SERVER_ERROR:
            default:
                return R.string.queue_play_error;
        }
    }

    private boolean connected() {
        return connectionStatus == RemoteClientController.Status.CONNECTED
                || connectionStatus == RemoteClientController.Status.CONNECTED_DIRECT;
    }

    private void saveScrollPosition() {
        if (listView == null || listView.getVisibility() != View.VISIBLE
                || listView.getChildCount() == 0) return;
        firstVisible = listView.getFirstVisiblePosition();
        topOffset = listView.getChildAt(0).getTop();
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
            artworkBindingLifecycle++;
            requestGate.invalidate();
            loading = false;
            playRequestSerial++;
            if (serverChanged) {
                queueAddOperationId = 0L;
                queueAddFromSelection = false;
                queueAddInFlight = false;
                capabilities = LibrarySortCapabilities.defaultOnly();
                capabilitiesGeneration = Integer.MIN_VALUE;
                capabilitiesSerial++;
                selection.clear();
                pager.reset();
                loadedQueueContentGeneration = -1L;
                failure = null;
                firstVisible = 0;
                topOffset = 0;
            }
            adapter.notifyDataSetChanged();
        }
        if (connected()) requestCapabilities();
        if (connected() && pager.initialized() && controller != null
                && loadedQueueContentGeneration >= 0L
                && loadedQueueContentGeneration != controller.queueContentGeneration()) {
            reload(false);
            return;
        }
        if (connected() && (!wasConnected || generationChanged)) {
            if (failure == RemoteClientController.LibraryFailure.DISCONNECTED) failure = null;
            if (!pager.initialized() && failure == null) {
                loadPage(false);
                return;
            }
        }
        render();
        if (connected() && pager.initialized()) {
            listView.setSelectionFromTop(firstVisible, topOffset);
        }
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
            RemoteClientController.LibraryFailure addFailure
    ) {
        queueAddInFlight = false;
        boolean ownOperation = operationId == queueAddOperationId;
        boolean fromSelection = ownOperation && queueAddFromSelection;
        if (ownOperation) {
            queueAddOperationId = 0L;
            queueAddFromSelection = false;
        }
        if (!started || controller == null) return;
        if (ownOperation) {
            if (addFailure != null || result == null) {
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
        }
        if (result != null && result.addedCount > 0
                && pager.initialized()
                && loadedQueueContentGeneration >= 0L
                && loadedQueueContentGeneration != controller.queueContentGeneration()) {
            reload(false);
        } else {
            render();
        }
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

    private final class QueueAdapter extends BaseAdapter {
        private List<LibraryItem> items = new ArrayList<>();
        private final CurrentTrackMatcher.IndicatorState currentTrack =
                new CurrentTrackMatcher.IndicatorState();
        private final int placeholderPadding = Math.round(
                10f * getResources().getDisplayMetrics().density
        );

        void setItems(List<LibraryItem> items) {
            if (sameItems(this.items, items)) return;
            this.items = new ArrayList<>(items);
            notifyDataSetChanged();
        }

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

        @Override public int getCount() { return items.size(); }
        @Override public LibraryItem getItem(int position) {
            return position < 0 || position >= items.size() ? null : items.get(position);
        }
        @Override public long getItemId(int position) {
            LibraryItem item = getItem(position);
            return item != null && item.entryId != null ? item.entryId : position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(QueueActivity.this)
                        .inflate(R.layout.library_list_item, parent, false);
                holder = new Holder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (Holder) convertView.getTag();
            }
            LibraryItem item = getItem(position);
            bindPrimaryInteraction(holder, item);
            if (item == null) {
                holder.root.setActivated(false);
                resetMenu(holder.menu);
                holder.title.setText("");
                setOptional(holder.subtitle, null);
                setOptional(holder.detail, null);
                renderCurrentIndicator(holder, null);
                holder.artworkGate.bind(null);
                holder.artwork.setVisibility(View.INVISIBLE);
                return convertView;
            }
            holder.root.setActivated(selection.contains(item.playTarget));
            boolean menuVisible = selection.isEmpty() && canAdd(item) && !queueAddInFlight;
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
            holder.title.setText(displayTitle(item));
            ArrayList<String> subtitle = new ArrayList<>(2);
            addDistinct(subtitle, item.artist);
            addDistinct(subtitle, item.album);
            setOptional(holder.subtitle, join(subtitle));
            if (item.durationMilliseconds == null) {
                setOptional(holder.detail, null);
            } else {
                long seconds = item.durationMilliseconds / 1_000L;
                setOptional(holder.detail, TimeFormatter.formatSeconds(
                        (int) Math.min(seconds, Integer.MAX_VALUE)
                ));
            }
            renderCurrentIndicator(holder, item);
            renderArtwork(holder, item);
            return convertView;
        }

        private void bindPrimaryInteraction(Holder holder, LibraryItem item) {
            boolean available = item != null && item.playTarget != null;
            boolean longEnabled = available && canAdd(item) && !queueAddInFlight;
            holder.root.setEnabled(available);
            holder.root.setClickable(available);
            holder.root.setLongClickable(longEnabled);
            holder.root.setSelected(false);
            holder.root.setContentDescription(null);
            holder.root.setOnClickListener(available ? view -> {
                TrackRowInteractionPolicy.Action action =
                        TrackRowInteractionPolicy.tap(
                                !selection.isEmpty(),
                                canAdd(item),
                                !queueAddInFlight
                        );
                if (action == TrackRowInteractionPolicy.Action.TOGGLE_SELECTION) {
                    toggleSelection(item, view);
                } else if (action == TrackRowInteractionPolicy.Action.OPEN) {
                    haptic(view);
                    play(item.playTarget);
                }
            } : null);
            holder.root.setOnLongClickListener(longEnabled ? view -> {
                TrackRowInteractionPolicy.Action action =
                        TrackRowInteractionPolicy.longPress(
                                canAdd(item),
                                !queueAddInFlight
                        );
                if (action != TrackRowInteractionPolicy.Action.TOGGLE_SELECTION) {
                    return false;
                }
                toggleSelection(item, view);
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

        private void renderCurrentIndicator(Holder holder, LibraryItem item) {
            boolean current = item != null && currentTrack.matches(item);
            holder.current.setVisibility(current ? View.VISIBLE : View.INVISIBLE);
            holder.current.setContentDescription(current
                    ? getString(R.string.queue_current_entry) : null);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                holder.root.setStateDescription(current
                        ? getString(R.string.queue_current_entry) : null);
            }
        }

        private void renderArtwork(Holder holder, LibraryItem item) {
            holder.artwork.setVisibility(View.VISIBLE);
            LibraryArtworkKey artworkKey = controller == null
                    ? null : controller.libraryArtworkKey(item.artworkPath);
            LibraryArtworkBindingGate.Request binding =
                    holder.artworkGate.bind(artworkKey, artworkBindingLifecycle);
            Bitmap bitmap = controller == null
                    ? null : controller.cachedLibraryArtwork(artworkKey);
            if (bitmap != null) {
                holder.artworkGate.complete(binding, artworkKey, true);
                holder.artwork.setPadding(0, 0, 0, 0);
                holder.artwork.setImageBitmap(bitmap);
                return;
            }
            if (holder.artworkGate.artworkDisplayed()) return;
            holder.artwork.setPadding(
                    placeholderPadding,
                    placeholderPadding,
                    placeholderPadding,
                    placeholderPadding
            );
            holder.artwork.setImageResource(R.drawable.ic_album_placeholder);
            PhoneConnectionService.LocalBinder active = controller;
            if (active == null || artworkKey == null || !holder.artworkGate.begin(binding)) return;
            int generation = active.libraryConnectionGeneration();
            active.requestLibraryArtwork(
                    item.artworkPath,
                    (resultGeneration, resultKey, artwork, artworkFailure) -> {
                        if (!started || controller == null
                                || resultGeneration != generation
                                || resultGeneration != controller.libraryConnectionGeneration()
                                || !holder.artworkGate.complete(
                                        binding, resultKey, artwork != null
                                )) return;
                        if (artwork != null) {
                            holder.artwork.setPadding(0, 0, 0, 0);
                            holder.artwork.setImageBitmap(artwork);
                        }
                    }
            );
        }

        private boolean sameItems(List<LibraryItem> first, List<LibraryItem> second) {
            if (first.size() != second.size()) return false;
            for (int index = 0; index < first.size(); index++) {
                if (first.get(index) != second.get(index)) return false;
            }
            return true;
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

    private String displayTitle(LibraryItem item) {
        return item.title == null || item.title.trim().isEmpty()
                ? getString(R.string.library_unknown_item) : item.title;
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
