package com.gathin.tvhome;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class MoonlightShelfReloadTest {
    @Test public void resumeDuringLoadReloadsWhenCatalogChangedAfterRead() {
        assertTrue(MoonlightShelf.needsReload(true, 100L, 200L));
    }
    @Test public void resumeDuringLoadKeepsResultWhenCatalogUnchanged() {
        assertFalse(MoonlightShelf.needsReload(true, 100L, 100L));
    }
    @Test public void finishWithoutPendingResumeNeverForcesReload() {
        assertFalse(MoonlightShelf.needsReload(false, 100L, 200L));
        assertFalse(MoonlightShelf.needsReload(false, 100L, 100L));
    }
}
