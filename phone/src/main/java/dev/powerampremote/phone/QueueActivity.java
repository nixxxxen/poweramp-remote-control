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
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import java.util.ArrayList;
import java.util.List;

/** Read-only Queue presentation backed only by the existing Phone connection service. */
public final class QueueActivity extends LocaleAwareActivity
        implements PhoneConnectionService.Listener {
    private final LibraryPager pager = new LibraryPager();
    private final QueueRequestGate requestGate = new QueueRequestGate();

    private ListView listView;
    private ProgressBar progress;
    private TextView statusMessage;
    private Button actionButton;
    private ImageButton reloadButton;
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
            miniPlayer.attach(controller);
            adapter.notifyDataSetChanged();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            controller = null;
            miniPlayer.onServiceDisconnected();
            connectionStatus = RemoteClientController.Status.ERROR;
            requestGate.invalidate();
            loading = false;
            playRequestSerial++;
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
        miniPlayer = new MiniPlayerController(this);
        adapter = new QueueAdapter();
        listView.setAdapter(adapter);

        findViewById(R.id.queue_back_button).setOnClickListener(view -> {
            haptic(view);
            finish();
        });
        reloadButton.setOnClickListener(view -> {
            haptic(view);
            reload();
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            LibraryItem item = adapter.getItem(position);
            if (item == null || item.playTarget == null) return;
            haptic(view);
            play(item.playTarget);
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
            @Override public void handleOnBackPressed() { finish(); }
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
                        } catch (IllegalArgumentException exception) {
                            failure = RemoteClientController.LibraryFailure.SERVER_ERROR;
                        }
                    }
                    render();
                }
        );
    }

    private void reload() {
        requestGate.invalidate();
        loading = false;
        failure = null;
        pager.reset();
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

    private void render() {
        BottomNavigation.bindAuxiliary(
                this, BottomNavigation.Tab.PLAYER, findViewById(R.id.queue_content)
        );
        adapter.setItems(pager.items());
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
                reload();
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
                pager.reset();
                failure = null;
                firstVisible = 0;
                topOffset = 0;
            }
            adapter.notifyDataSetChanged();
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
            if (item == null) return convertView;
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
        final TextView title;
        final TextView subtitle;
        final TextView detail;
        final LibraryArtworkBindingGate artworkGate = new LibraryArtworkBindingGate();

        Holder(View view) {
            root = view;
            artwork = view.findViewById(R.id.library_item_artwork);
            current = view.findViewById(R.id.library_item_current);
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
