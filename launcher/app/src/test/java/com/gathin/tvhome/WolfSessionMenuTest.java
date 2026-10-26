package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Looper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public class WolfSessionMenuTest {
    static final String REV=String.join("",Collections.nCopies(64,"a"));
    Activity activity;
    Fake control;
    Manual executor;
    WolfSessionMenu menu;
    final List<String> launches=new ArrayList<>();
    final List<WolfSession> states=new ArrayList<>();
    int focus;
    static class Fake implements WolfControl {
        WolfSession state=new WolfSession(true,"10","Mario Kart",REV);
        String error;int reads,stops;boolean keepActive;
        @Override public WolfSession getSession() throws WolfControlClient.ControlException {
            reads++;if(error!=null)throw new WolfControlClient.ControlException(error);return state;
        }
        @Override public WolfSession stop(WolfSession expected) throws WolfControlClient.ControlException {
            stops++;if(error!=null)throw new WolfControlClient.ControlException(error);
            if(!keepActive)state=WolfSession.inactive();return state;
        }
    }
    static class Manual extends AbstractExecutorService {
        final ArrayDeque<Runnable> jobs=new ArrayDeque<>();boolean closed;
        @Override public void execute(Runnable task){if(closed)throw new RejectedExecutionException();jobs.add(task);}
        void run(){jobs.remove().run();Shadows.shadowOf(Looper.getMainLooper()).idle();}
        @Override public void shutdown(){closed=true;}
        @Override public List<Runnable> shutdownNow(){closed=true;List<Runnable> result=new ArrayList<>(jobs);jobs.clear();return result;}
        @Override public boolean isShutdown(){return closed;}
        @Override public boolean isTerminated(){return closed&&jobs.isEmpty();}
        @Override public boolean awaitTermination(long n,TimeUnit unit){return isTerminated();}
    }
    WolfSessionMenu.Listener listener(){return new WolfSessionMenu.Listener(){
        @Override public void launch(String id,String name){launches.add(id+":"+name);}
        @Override public void focusCarousel(){focus++;}
        @Override public void sessionState(WolfSession session){states.add(session);}
    };}
    @Before public void setup(){
        activity=Robolectric.buildActivity(Activity.class).setup().visible().get();
        activity.getSharedPreferences("moonlight",0).edit().clear().commit();
        control=new Fake();executor=new Manual();menu=new WolfSessionMenu(activity,listener(),()->control,executor);menu.resume();executor.run();control.reads=0;states.clear();
    }
    @After public void cleanup(){menu.close();activity.finish();}
    AlertDialog dialog(){return ShadowAlertDialog.getLatestAlertDialog();}
    void choose(int index){Shadows.shadowOf(dialog()).clickOnItem(index);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    void confirm(){dialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void inactiveLaunchesDirectlyWithoutStop(){
        control.state=WolfSession.inactive();menu.open("20","Donkey Kong");executor.run();
        assertEquals(Collections.singletonList("20:Donkey Kong"),launches);assertEquals(0,control.stops);
        assertTrue(activity.getSharedPreferences("moonlight",0).getBoolean(WolfSessionMenu.RETURN_KEY,false));
    }
    @Test public void sameGameResumeNeverSendsStop(){
        menu.open("10","Mario Kart");executor.run();
        assertEquals("Retomar jogo",Shadows.shadowOf(dialog()).getItems()[0]);
        choose(0);executor.run();assertEquals(1,launches.size());assertEquals(0,control.stops);assertEquals(2,control.reads);
    }
    @Test public void switchWaitsForConfirmationAndVerifiedInactive(){
        menu.open("20","Donkey Kong");executor.run();
        assertTrue(Shadows.shadowOf(dialog()).getMessage().toString().contains("progresso não salvo"));
        assertEquals(0,control.stops);assertTrue(launches.isEmpty());
        confirm();assertTrue(launches.isEmpty());executor.run();
        assertEquals(1,control.stops);assertEquals(Collections.singletonList("20:Donkey Kong"),launches);
    }
    @Test public void cancellingSwitchLeavesGameAlone(){
        menu.open("20","Donkey Kong");executor.run();dialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,control.stops);assertTrue(launches.isEmpty());
    }
    @Test public void returnHighlightsWithoutPopupAndCardOpensOptions(){
        menu.pause();activity.getSharedPreferences("moonlight",0).edit().putBoolean(WolfSessionMenu.RETURN_KEY,true).commit();
        menu.resume();Shadows.shadowOf(Looper.getMainLooper()).idle();executor.run();
        assertEquals(1,states.size());assertTrue(states.get(0).active);
        assertFalse(dialog()!=null && dialog().isShowing());
        menu.open("10","Mario Kart");executor.run();
        assertEquals("Retomar jogo",Shadows.shadowOf(dialog()).getItems()[0]);
        assertEquals("Encerrar jogo",Shadows.shadowOf(dialog()).getItems()[1]);
        choose(2);
        assertEquals(0,control.stops);assertTrue(launches.isEmpty());assertTrue(control.state.active);
        menu.pause();menu.resume();executor.run();assertFalse(dialog()!=null && dialog().isShowing());
    }
    @Test public void explicitStopDoesNotLaunchAnyGame(){
        menu.open("10","Mario Kart");executor.run();choose(1);confirm();executor.run();
        assertEquals(1,control.stops);assertFalse(control.state.active);assertTrue(launches.isEmpty());
    }
    @Test public void conflictingOrFailedStopNeverLaunchesTarget(){
        for(String failure:new String[]{"session_changed","stop_timeout","unauthorized","network_error","tls_error","config_error","upstream_unavailable","busy","protocol_error","invalid_request","unknown-sensitive-detail"}){
            control.error=null;menu.open("20","Donkey Kong");executor.run();
            control.error=failure;confirm();executor.run();assertTrue(launches.isEmpty());
            assertFalse(Shadows.shadowOf(dialog()).getMessage().toString().contains("unknown-sensitive-detail"));
            dialog().dismiss();
        }
    }
    @Test public void activeResponseToStopIsNotSuccess(){
        control.keepActive=true;menu.open("20","Donkey Kong");executor.run();confirm();executor.run();
        assertTrue(launches.isEmpty());assertTrue(control.state.active);
    }
    @Test public void resumeRechecksIdentityBeforeLaunching(){
        menu.open("10","Mario Kart");executor.run();choose(0);
        control.state=new WolfSession(true,"20","Donkey Kong",REV);executor.run();assertTrue(launches.isEmpty());assertEquals(0,control.stops);
    }
    @Test public void duplicateAndCancelledRequestsDoNotAccumulate(){
        menu.open("20","Donkey Kong");menu.open("20","Donkey Kong");assertEquals(1,executor.jobs.size());
        dialog().cancel();menu.open("20","Donkey Kong");assertEquals(1,executor.jobs.size());executor.run();
        assertTrue(launches.isEmpty());assertEquals(1,control.reads);
    }
    @Test public void pausedResultCannotOpenDialogOrLaunch(){
        menu.open("20","Donkey Kong");menu.pause();executor.run();assertTrue(launches.isEmpty());assertFalse(dialog().isShowing());
        menu.resume();executor.run();control.state=WolfSession.inactive();menu.open("20","Donkey Kong");executor.run();assertEquals(1,launches.size());
    }
    @Test public void confirmedStopFinishesButNeverLaunchesAfterPause(){
        menu.open("20","Donkey Kong");executor.run();confirm();menu.pause();executor.run();
        assertEquals(1,control.stops);assertTrue(launches.isEmpty());
    }
    @Test public void rejectedExecutorAndInvalidTargetsFailClosed(){
        menu.open("0","Invalid");assertTrue(executor.jobs.isEmpty());assertTrue(launches.isEmpty());
        executor.shutdown();menu.open("20","Donkey Kong");assertTrue(launches.isEmpty());
    }
    @Test public void requestWhileNotResumedIsIgnored(){
        menu.pause();menu.open("20","Donkey Kong");assertTrue(executor.jobs.isEmpty());assertTrue(launches.isEmpty());
    }
    @Test public void returnAfterGameAlreadyEndedDoesNotShowActiveMenu(){
        menu.pause();control.state=WolfSession.inactive();activity.getSharedPreferences("moonlight",0).edit().putBoolean(WolfSessionMenu.RETURN_KEY,true).commit();
        menu.resume();Shadows.shadowOf(Looper.getMainLooper()).idle();executor.run();assertFalse(dialog()!=null && dialog().isShowing());assertFalse(states.get(states.size()-1).active);assertTrue(launches.isEmpty());
    }
}
