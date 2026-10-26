package com.gathin.tvhome;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public class MoonlightShelfReloadTest {
    static class Manual extends AbstractExecutorService {
        final ArrayDeque<Runnable> jobs=new ArrayDeque<>();boolean closed;int submissions;
        @Override public void execute(Runnable task){if(closed)throw new RejectedExecutionException();submissions++;jobs.add(task);}
        void run(){jobs.remove().run();Shadows.shadowOf(Looper.getMainLooper()).idle();}
        @Override public void shutdown(){closed=true;}
        @Override public List<Runnable> shutdownNow(){closed=true;List<Runnable> result=new ArrayList<>(jobs);jobs.clear();return result;}
        @Override public boolean isShutdown(){return closed;}
        @Override public boolean isTerminated(){return closed&&jobs.isEmpty();}
        @Override public boolean awaitTermination(long n,TimeUnit unit){return isTerminated();}
    }
    private static void writeCatalog(File file,String game,long stamp) throws Exception {
        String json="{\"schema_version\":1,\"host_name\":\"PC\",\"host_uuid\":\"11111111-2222-4333-8444-555555555555\",\"games\":[{\"name\":\""+game+"\",\"app_id\":\"10\"}]}";
        Files.write(file.toPath(),json.getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setLastModified(stamp));assertEquals(stamp,file.lastModified());
    }
    private static boolean hasText(View view,String text) {
        if(view instanceof TextView && text.equals(((TextView)view).getText().toString()))return true;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)if(hasText(group.getChildAt(i),text))return true;
        }
        return false;
    }
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
    @Test public void resumeDuringLoadReloadsFromCallbackWhenCatalogChanges() throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().visible().get();
        Manual loader=new Manual();MoonlightShelf shelf=null;
        try {
            PackageInfo moonlight=new PackageInfo();moonlight.packageName="com.limelight";
            moonlight.applicationInfo=new ApplicationInfo();moonlight.applicationInfo.packageName="com.limelight";
            Shadows.shadowOf(activity.getPackageManager()).addPackage(moonlight);
            activity.getSharedPreferences("moonlight",0).edit().clear().commit();
            File directory=new File(activity.getExternalFilesDir(null),"moonlight");
            assertTrue(directory.mkdirs() || directory.isDirectory());
            File catalog=new File(directory,"catalog.json");
            writeCatalog(catalog,"Alpha",1_700_000_000_000L);
            FrameLayout root=new FrameLayout(activity);
            RecyclerView grid=new RecyclerView(activity);grid.setLayoutManager(new LinearLayoutManager(activity));
            View hero=new View(activity),glass=new View(activity);
            root.addView(hero);root.addView(glass);
            shelf=new MoonlightShelf(activity,root,grid,hero,glass,loader);
            shelf.resume();
            assertEquals(1,loader.submissions);
            writeCatalog(catalog,"Beta",1_700_000_060_000L);
            shelf.resume();
            assertEquals(1,loader.submissions);
            loader.run();
            assertEquals(2,loader.submissions);
            loader.run();
            assertTrue(shelf.active());assertTrue(hasText(root,"Beta"));
        } finally {if(shelf!=null)shelf.close();activity.finish();}
    }
}
