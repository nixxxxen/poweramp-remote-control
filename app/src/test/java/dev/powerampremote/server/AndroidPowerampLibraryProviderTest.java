package dev.powerampremote.server;

import android.os.DeadObjectException;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class AndroidPowerampLibraryProviderTest {
    @Test
    public void deadObjectExceptionMapsToProviderUnavailable() {
        assertEquals(
                PowerampLibraryProvider.Failure.UNAVAILABLE,
                AndroidPowerampLibraryProvider.remoteFailure(new DeadObjectException())
        );
    }

    @Test
    public void closesCursorBeforeUnstableClientEvenWhenCursorCloseFails() {
        List<String> closeOrder = new ArrayList<>();

        AndroidPowerampLibraryProvider.closeInOrder(
                () -> {
                    closeOrder.add("cursor");
                    throw new IllegalStateException("cursor close failed");
                },
                () -> closeOrder.add("unstable-client")
        );

        assertEquals(List.of("cursor", "unstable-client"), closeOrder);
    }
}
