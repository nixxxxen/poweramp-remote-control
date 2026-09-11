package dev.powerampremote.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class QueuePresentationPolicyTest {
    @Test
    public void disconnectAndContinuationFailureKeepLoadedRowsVisible() {
        QueuePresentationPolicy.Model disconnected = QueuePresentationPolicy.resolve(
                false, true, false, 25, true, false, null
        );
        assertTrue(disconnected.rowsVisible);
        assertEquals(
                QueuePresentationPolicy.Message.DISCONNECTED, disconnected.message
        );
        assertEquals(QueuePresentationPolicy.Action.NONE, disconnected.action);

        QueuePresentationPolicy.Model expired = QueuePresentationPolicy.resolve(
                true,
                true,
                false,
                25,
                true,
                false,
                RemoteClientController.LibraryFailure.PAGE_EXPIRED
        );
        assertTrue(expired.rowsVisible);
        assertEquals(QueuePresentationPolicy.Message.PAGE_EXPIRED, expired.message);
        assertEquals(QueuePresentationPolicy.Action.RELOAD, expired.action);
    }

    @Test
    public void loadingEmptyAndNormalContinuationHaveDistinctPresentation() {
        QueuePresentationPolicy.Model loading = QueuePresentationPolicy.resolve(
                true, false, true, 0, false, false, null
        );
        assertTrue(loading.progressVisible);
        assertEquals(QueuePresentationPolicy.Message.LOADING, loading.message);

        QueuePresentationPolicy.Model empty = QueuePresentationPolicy.resolve(
                true, true, false, 0, false, false, null
        );
        assertFalse(empty.rowsVisible);
        assertEquals(QueuePresentationPolicy.Message.EMPTY, empty.message);

        QueuePresentationPolicy.Model more = QueuePresentationPolicy.resolve(
                true, true, false, 25, true, false, null
        );
        assertTrue(more.rowsVisible);
        assertEquals(QueuePresentationPolicy.Message.NONE, more.message);
        assertEquals(QueuePresentationPolicy.Action.LOAD_MORE, more.action);
    }

    @Test
    public void permissionProviderAndUnsupportedStatesAreExplicit() {
        assertState(
                RemoteClientController.LibraryFailure.PERMISSION_REQUIRED,
                QueuePresentationPolicy.Message.PERMISSION_REQUIRED,
                QueuePresentationPolicy.Action.RETRY
        );
        assertState(
                RemoteClientController.LibraryFailure.PROVIDER_UNAVAILABLE,
                QueuePresentationPolicy.Message.PROVIDER_UNAVAILABLE,
                QueuePresentationPolicy.Action.RETRY
        );
        assertState(
                RemoteClientController.LibraryFailure.UNSUPPORTED,
                QueuePresentationPolicy.Message.UNSUPPORTED,
                QueuePresentationPolicy.Action.NONE
        );
    }

    private static void assertState(
            RemoteClientController.LibraryFailure failure,
            QueuePresentationPolicy.Message message,
            QueuePresentationPolicy.Action action
    ) {
        QueuePresentationPolicy.Model model = QueuePresentationPolicy.resolve(
                true, false, false, 0, false, false, failure
        );
        assertEquals(message, model.message);
        assertEquals(action, model.action);
    }
}
