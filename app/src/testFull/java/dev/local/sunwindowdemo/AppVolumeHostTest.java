package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.media.AudioAttributes;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows=WindowAudioTest.PlaybackSnapshot.class)
public class AppVolumeHostTest {
    private static final int SYSTEM_UI=10170, GAME=10266, OTHER=10267;
    private final Binder owner=new Binder();
    private Context context;
    private WindowAudioGain gain;
    private AppVolumeHost host;

    @Before public void setUp()throws Exception{
        context=RuntimeEnvironment.getApplication();
        install("com.android.systemui",SYSTEM_UI,true);
        install("game.example",GAME,false);
        install("other.example",OTHER,false);
        gain=WindowAudioTest.gain();
        WindowAudioTest.PlaybackSnapshot.configs=List.of();
        host=new AppVolumeHost(context,new Handler(Looper.getMainLooper()),gain);
    }
    private void install(String pkg,int uid,boolean system){
        PackageInfo info=new PackageInfo();info.packageName=pkg;
        info.applicationInfo=new ApplicationInfo();info.applicationInfo.packageName=pkg;
        info.applicationInfo.uid=uid;if(system)info.applicationInfo.flags|=ApplicationInfo.FLAG_SYSTEM;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
    }
    private Intent command(int level,boolean reset){
        Bundle extras=new Bundle();extras.putBinder("owner",owner);
        return new Intent(AppVolumeHost.ACTION).putExtras(extras).putExtra("uid",GAME)
                .putExtra("package","game.example").putExtra("level",level).putExtra("reset",reset);
    }
    private WindowAudioTest.NativePlayer player(int id,int uid)throws Exception{
        var config=WindowAudioTest.config(id,uid);
        var field=config.getClass().getDeclaredField("mPlayerAttr");field.setAccessible(true);
        field.set(config,new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build());
        WindowAudioTest.players(gain).put(id,new WindowAudioGain.Player(uid));
        var snapshot=new java.util.ArrayList<>(WindowAudioTest.PlaybackSnapshot.configs);
        snapshot.add(config);WindowAudioTest.PlaybackSnapshot.configs=snapshot;
        return WindowAudioTest.nativePlayer(config);
    }
    @Test public void unknownMediaAndGameAreEligibleButCallsAlarmsAndNotificationsAreNot(){
        for(int usage:new int[]{AudioAttributes.USAGE_UNKNOWN,AudioAttributes.USAGE_MEDIA,AudioAttributes.USAGE_GAME})
            assertTrue(AppVolumeHost.media(new AudioAttributes.Builder().setUsage(usage).build()));
        for(int usage:new int[]{AudioAttributes.USAGE_VOICE_COMMUNICATION,AudioAttributes.USAGE_ALARM,AudioAttributes.USAGE_NOTIFICATION})
            assertFalse(AppVolumeHost.media(new AudioAttributes.Builder().setUsage(usage).build()));
    }
    @Test public void selectedUidAndNewPlayersInheritGainWithoutChangingOtherApp()throws Exception{
        var first=player(1,GAME);var other=player(2,OTHER);
        assertTrue(host.receive(SYSTEM_UI,command(40,false)));
        assertEquals(List.of(.4f),first.values);assertTrue(other.values.isEmpty());
        var next=player(3,GAME);gain.onAppBorn.run();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(.4f),next.values);
        assertTrue(host.receive(SYSTEM_UI,command(100,true)));
        assertEquals(List.of(.4f,1f),first.values);assertEquals(List.of(.4f,1f),next.values);
    }
    @Test public void forgedSenderWrongPackageSharedUidAndOutOfRangeGainAreRejected()throws Exception{
        var first=player(1,GAME);
        assertFalse(host.receive(OTHER,command(10,false)));
        assertFalse(host.receive(SYSTEM_UI,command(-1,false)));
        assertFalse(host.receive(SYSTEM_UI,command(101,false)));
        assertFalse(host.receive(SYSTEM_UI,command(10,false).putExtra("package","other.example")));
        assertFalse(host.receive(SYSTEM_UI,command(10,false).putExtra("uid",110266)));
        install("shared.example",GAME,false);
        assertFalse(host.receive(SYSTEM_UI,command(10,false)));
        assertTrue(first.values.isEmpty());
    }
    @Test public void failedDisableIsRetriedWithoutAnotherPlaybackEvent()throws Exception{
        var first=player(1,GAME);
        assertTrue(host.receive(SYSTEM_UI,command(30,false)));
        first.fail=true;assertFalse(host.receive(SYSTEM_UI,command(100,true)));
        first.fail=false;Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertEquals(List.of(.3f,1f),first.values);
    }
    @Test public void unknownBirthIsNotReportedAsAdjustedAndConfirmedBirthRetries()throws Exception{
        var first=player(1,GAME);
        WindowAudioTest.players(gain).clear();
        assertFalse(host.receive(SYSTEM_UI,command(40,false)));
        assertTrue(first.values.isEmpty());
        WindowAudioTest.players(gain).put(1,new WindowAudioGain.Player(GAME));
        gain.onAppBorn.run();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(.4f),first.values);
    }
    @Test public void clientDeathRestoresGainAndLateOldDeathDoesNotClearNewOwner()throws Exception{
        var first=player(1,GAME);
        assertTrue(host.receive(SYSTEM_UI,command(40,false)));
        var field=AppVolumeHost.class.getDeclaredField("death");field.setAccessible(true);
        var oldDeath=(android.os.IBinder.DeathRecipient)field.get(host);
        oldDeath.binderDied();
        Bundle extras=new Bundle();extras.putBinder("owner",new Binder());
        assertTrue(host.receive(SYSTEM_UI,command(30,false).putExtras(extras)));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(.4f,1f,.3f),first.values);
        ((android.os.IBinder.DeathRecipient)field.get(host)).binderDied();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(.4f,1f,.3f,1f),first.values);
    }
}
