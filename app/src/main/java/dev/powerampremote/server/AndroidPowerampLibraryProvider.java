package dev.powerampremote.server;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.DeadObjectException;
import android.os.OperationCanceledException;
import android.os.RemoteException;
import android.util.Log;

/** Android adapter for the documented Poweramp ContentProvider query surface. */
final class AndroidPowerampLibraryProvider implements PowerampLibraryProvider {
    private static final String TAG = "PowerampLibrary";

    interface CloseOperation {
        void close();
    }

    private final Context context;
    private final ContentResolver resolver;

    AndroidPowerampLibraryProvider(Context context) {
        this.context = context.getApplicationContext();
        resolver = this.context.getContentResolver();
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isPowerampInstalled() {
        try {
            context.getPackageManager().getApplicationInfo(PowerampContract.PACKAGE_NAME, 0);
            return true;
        } catch (PackageManager.NameNotFoundException exception) {
            return false;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to verify Poweramp installation", exception);
            return false;
        }
    }

    @Override
    public Rows query(
            PowerampLibraryContract.Query query,
            int limit,
            LibraryCancellation cancellation
    ) throws ProviderException {
        CancellationSignal signal = new CancellationSignal();
        cancellation.attach(signal::cancel);
        ContentProviderClient client = null;
        Cursor cursor = null;
        try {
            if (cancellation.isCancelled()) {
                throw new ProviderException(Failure.CANCELLED);
            }
            Uri providerUri = Uri.parse(query.providerUri(limit));
            client = resolver.acquireUnstableContentProviderClient(providerUri);
            if (client == null) {
                throw new ProviderException(Failure.UNAVAILABLE);
            }
            cursor = client.query(
                    providerUri,
                    query.projection(),
                    query.selection(),
                    query.selectionArgs(),
                    null,
                    signal
            );
            if (cursor == null) {
                throw new ProviderException(Failure.UNAVAILABLE);
            }
            Rows rows = new CursorRows(cursor, client, cancellation, query);
            cursor = null;
            client = null;
            return rows;
        } catch (DeadObjectException exception) {
            closeQuietly(cursor, client);
            cancellation.detach();
            Log.w(TAG, "Poweramp ContentProvider process became unavailable");
            throw new ProviderException(remoteFailure(exception));
        } catch (RemoteException exception) {
            closeQuietly(cursor, client);
            cancellation.detach();
            Log.w(TAG, "Poweramp ContentProvider transport became unavailable");
            throw new ProviderException(remoteFailure(exception));
        } catch (SecurityException exception) {
            closeQuietly(cursor, client);
            cancellation.detach();
            Log.w(TAG, "Poweramp library permission is unavailable");
            throw new ProviderException(Failure.PERMISSION_REQUIRED);
        } catch (OperationCanceledException exception) {
            closeQuietly(cursor, client);
            cancellation.detach();
            throw new ProviderException(Failure.CANCELLED);
        } catch (ProviderException exception) {
            closeQuietly(cursor, client);
            cancellation.detach();
            throw exception;
        } catch (RuntimeException exception) {
            closeQuietly(cursor, client);
            cancellation.detach();
            // Provider exception messages may echo the search URI. Log only the failure class.
            Log.e(TAG, "Poweramp ContentProvider query failed: "
                    + exception.getClass().getSimpleName());
            throw new ProviderException(Failure.ERROR);
        }
    }

    @Override
    public QueueEditor openQueueEditor(LibraryCancellation cancellation)
            throws ProviderException {
        if (cancellation.isCancelled()) {
            throw new ProviderException(Failure.CANCELLED);
        }
        Uri queueUri = Uri.parse(PowerampLibraryContract.queueMutationUri());
        ContentProviderClient client = null;
        try {
            client = resolver.acquireUnstableContentProviderClient(queueUri);
            if (client == null) throw new ProviderException(Failure.UNAVAILABLE);
            QueueEditor editor = new AndroidQueueEditor(
                    context, queueUri, client, cancellation
            );
            client = null;
            return editor;
        } catch (SecurityException exception) {
            closeQuietly(null, client);
            throw new ProviderException(Failure.PERMISSION_REQUIRED);
        } catch (ProviderException exception) {
            closeQuietly(null, client);
            throw exception;
        } catch (RuntimeException exception) {
            closeQuietly(null, client);
            Log.e(TAG, "Unable to open Poweramp Queue editor: "
                    + exception.getClass().getSimpleName());
            throw new ProviderException(Failure.ERROR);
        }
    }

    static Failure remoteFailure(RemoteException ignored) {
        return Failure.UNAVAILABLE;
    }

    private static void closeQuietly(Cursor cursor, ContentProviderClient client) {
        closeInOrder(
                cursor == null ? null : cursor::close,
                client == null ? null : client::close
        );
    }

    static void closeInOrder(CloseOperation cursorClose, CloseOperation clientClose) {
        try {
            if (cursorClose != null) {
                cursorClose.close();
            }
        } catch (RuntimeException ignored) {
            // The provider already failed; preserve the sanitized primary failure.
        }
        try {
            if (clientClose != null) {
                clientClose.close();
            }
        } catch (RuntimeException ignored) {
            // The Cursor was already closed; no useful recovery remains for this client.
        }
    }

    private static final class CursorRows implements Rows {
        private final Cursor cursor;
        private final ContentProviderClient client;
        private final LibraryCancellation cancellation;
        private final PowerampLibraryContract.Query query;
        private boolean closed;

        CursorRows(
                Cursor cursor,
                ContentProviderClient client,
                LibraryCancellation cancellation,
                PowerampLibraryContract.Query query
        ) {
            this.cursor = cursor;
            this.client = client;
            this.cancellation = cancellation;
            this.query = query;
        }

        @Override
        public boolean moveToNext() throws ProviderException {
            if (closed || cancellation.isCancelled()) {
                throw new ProviderException(Failure.CANCELLED);
            }
            try {
                return cursor.moveToNext();
            } catch (OperationCanceledException exception) {
                throw new ProviderException(Failure.CANCELLED);
            } catch (RuntimeException exception) {
                Log.e(TAG, "Poweramp Cursor iteration failed: "
                        + exception.getClass().getSimpleName());
                throw new ProviderException(Failure.ERROR);
            }
        }

        @Override
        public Long longValue(String column) {
            try {
                int index = columnIndex(column);
                return index < 0 || cursor.isNull(index) ? null : cursor.getLong(index);
            } catch (RuntimeException exception) {
                return null;
            }
        }

        @Override
        public String textValue(String column) {
            try {
                int index = columnIndex(column);
                return index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
            } catch (RuntimeException exception) {
                return null;
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            cancellation.detach();
            closeQuietly(cursor, client);
        }

        private int columnIndex(String modelColumn) {
            for (String providerColumn : query.cursorColumns(modelColumn)) {
                int index = cursor.getColumnIndex(providerColumn);
                if (index >= 0) {
                    return index;
                }
            }
            return -1;
        }
    }

    private static final class AndroidQueueEditor implements QueueEditor {
        private final Context context;
        private final Uri queueUri;
        private final ContentProviderClient client;
        private final LibraryCancellation cancellation;
        private boolean closed;

        AndroidQueueEditor(
                Context context,
                Uri queueUri,
                ContentProviderClient client,
                LibraryCancellation cancellation
        ) {
            this.context = context;
            this.queueUri = queueUri;
            this.client = client;
            this.cancellation = cancellation;
        }

        @Override
        public Long maximumSort() throws ProviderException {
            ensureActive();
            CancellationSignal signal = new CancellationSignal();
            cancellation.attach(signal::cancel);
            Cursor cursor = null;
            try {
                cursor = client.query(
                        queueUri,
                        new String[]{PowerampLibraryContract.QUEUE_MAX_SORT_EXPRESSION},
                        null,
                        null,
                        null,
                        signal
                );
                if (cursor == null) throw new ProviderException(Failure.UNAVAILABLE);
                if (!cursor.moveToFirst() || cursor.isNull(0)) return null;
                return cursor.getLong(0);
            } catch (RemoteException exception) {
                throw new ProviderException(Failure.UNAVAILABLE);
            } catch (SecurityException exception) {
                throw new ProviderException(Failure.PERMISSION_REQUIRED);
            } catch (OperationCanceledException exception) {
                throw new ProviderException(Failure.CANCELLED);
            } catch (ProviderException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                Log.e(TAG, "Poweramp Queue MAX(sort) failed: "
                        + exception.getClass().getSimpleName());
                throw new ProviderException(Failure.ERROR);
            } finally {
                cancellation.detach();
                try {
                    if (cursor != null) cursor.close();
                } catch (RuntimeException ignored) {
                    // Preserve the primary sanitized provider result.
                }
            }
        }

        @Override
        public boolean insert(long folderFileId, long sort) throws ProviderException {
            ensureActive();
            if (folderFileId <= 0L || sort <= 0L) {
                throw new IllegalArgumentException("Queue IDs and sort must be positive");
            }
            ContentValues values = new ContentValues(2);
            values.put(PowerampLibraryContract.QUEUE_FOLDER_FILE_ID, folderFileId);
            values.put(PowerampLibraryContract.QUEUE_SORT, sort);
            try {
                return client.insert(queueUri, values) != null;
            } catch (RemoteException exception) {
                throw new ProviderException(Failure.UNAVAILABLE);
            } catch (SecurityException exception) {
                throw new ProviderException(Failure.PERMISSION_REQUIRED);
            } catch (RuntimeException exception) {
                Log.e(TAG, "Poweramp Queue insert failed: "
                        + exception.getClass().getSimpleName());
                throw new ProviderException(Failure.ERROR);
            }
        }

        @Override
        public void reload() throws ProviderException {
            if (closed) throw new ProviderException(Failure.CANCELLED);
            try {
                Intent intent = new Intent(PowerampContract.ACTION_RELOAD_DATA)
                        .setComponent(new ComponentName(
                                PowerampContract.PACKAGE_NAME,
                                PowerampContract.API_RECEIVER_NAME
                        ))
                        .putExtra(PowerampContract.EXTRA_PACKAGE, context.getPackageName())
                        .putExtra(
                                PowerampContract.EXTRA_TABLE,
                                PowerampLibraryContract.QUEUE_TABLE
                        );
                context.sendBroadcast(intent);
            } catch (SecurityException exception) {
                throw new ProviderException(Failure.PERMISSION_REQUIRED);
            } catch (RuntimeException exception) {
                Log.e(TAG, "Poweramp Queue reload failed: "
                        + exception.getClass().getSimpleName());
                throw new ProviderException(Failure.ERROR);
            }
        }

        private void ensureActive() throws ProviderException {
            if (closed || cancellation.isCancelled()) {
                throw new ProviderException(Failure.CANCELLED);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            cancellation.detach();
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // The request boundary already owns the operation result.
            }
        }
    }
}
