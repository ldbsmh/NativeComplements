package dev.local.sunwindowdemo;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows={BluetoothHeadsetVolumeTest.Routes.class,BluetoothHeadsetVolumeTest.Device.class})
public class BluetoothHeadsetVolumeTest {
    @Implements(AudioManager.class)
    public static class Routes extends ShadowAudioManager {
        static List<AudioDeviceInfo> devices;
        static AudioDeviceInfo switchTo;
        static int reads;
        @Implementation protected List<AudioDeviceInfo> getAudioDevicesForAttributes(AudioAttributes attributes){
            reads++;
            return switchTo!=null&&reads>=2?List.of(switchTo):devices;
        }
    }
    @Implements(AudioDeviceInfo.class)
    public static class Device {
        int id,type;
        @Implementation protected int getId(){return id;}
        @Implementation protected int getType(){return type;}
        @Implementation protected boolean isSink(){return true;}
    }
    private AudioManager audio;
    private BluetoothHeadsetVolume volume;
    private Handler worker;
    private final List<BluetoothHeadsetVolume.Hint> hints=new ArrayList<>();
    private AudioDeviceInfo device(int id,int type){
        var info=AudioDeviceInfoBuilder.newBuilder().setType(type).build();
        Device shadow=Shadow.extract(info);shadow.id=id;shadow.type=type;return info;
    }
    @Before public void setUp(){
        audio=RuntimeEnvironment.getApplication().getSystemService(AudioManager.class);
        Shadows.shadowOf(audio).setStreamMaxVolume(15);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,7,0);
        Routes.devices=List.of(device(1,AudioDeviceInfo.TYPE_BLE_HEADSET));Routes.switchTo=null;Routes.reads=0;
        volume=new BluetoothHeadsetVolume(RuntimeEnvironment.getApplication(),audio,new Handler(Looper.getMainLooper()),hints::add);
        worker=ReflectionHelpers.getField(volume,"worker");
    }
    private void advance(){
        Shadows.shadowOf(worker.getLooper()).idleFor(Duration.ofSeconds(1));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private void schedule(){ReflectionHelpers.callInstanceMethod(volume,"schedule");advance();}
    @After public void close(){volume.close();Shadows.shadowOf(worker.getLooper()).idle();}
    @Test public void capsOnceAndManualIncreaseIsKeptUntilAConnectionChange(){
        volume.configure(1);advance();
        assertEquals(1,audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        assertTrue(hints.stream().anyMatch(h->h!=null&&h.reduced()));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,8,0);schedule();
        assertEquals(8,audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        Routes.devices=List.of(device(2,AudioDeviceInfo.TYPE_BLE_HEADSET));schedule();
        assertEquals(1,audio.getStreamVolume(AudioManager.STREAM_MUSIC));
    }
    @Test public void speakerAndAlreadyMutedHeadsetArePreserved(){
        Routes.devices=List.of(device(2,AudioDeviceInfo.TYPE_BUILTIN_SPEAKER));volume.configure(1);advance();
        assertEquals(7,audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        Routes.devices=List.of(device(3,AudioDeviceInfo.TYPE_BLE_HEADSET));audio.setStreamVolume(AudioManager.STREAM_MUSIC,0,0);schedule();
        assertEquals(0,audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        assertFalse(hints.stream().anyMatch(h->h!=null&&h.reduced()));
    }
    @Test public void routeChangeDuringConfirmationDoesNotLowerSpeakerVolume(){
        Routes.switchTo=device(2,AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);volume.configure(1);advance();
        assertEquals(7,audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        assertFalse(hints.stream().anyMatch(h->h!=null&&h.reduced()));
    }
}
