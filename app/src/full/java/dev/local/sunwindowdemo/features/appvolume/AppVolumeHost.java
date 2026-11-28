package dev.local.sunwindowdemo;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** SystemUI-only AAudio attenuation, sharing the window/call multiplier owner. */
public final class AppVolumeHost {
    public static final String ACTION="dev.nativecomplements.module.APP_VOLUME_GAIN";
    private static final String SYSTEM_UI="com.android.systemui";
    private final Context context;
    private final Handler worker;
    private final WindowAudioGain audio;
    private final AudioManager manager;
    private final int systemUiUid;
    private final Map<Integer,Rule> rules=new HashMap<>();
    private IBinder owner;
    private IBinder.DeathRecipient death;
    private final Runnable retry=this::sync;
    private record Rule(String packageName,int level) {}

    AppVolumeHost(Context context,Handler worker,WindowAudioGain audio)throws Exception{
        this.context=context;this.worker=worker;this.audio=audio;
        manager=context.getSystemService(AudioManager.class);
        if(manager==null)throw new IllegalStateException("AudioManager missing");
        ApplicationInfo info=context.getPackageManager().getApplicationInfo(SYSTEM_UI,0);
        if((info.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))==0
                ||info.uid/100000!=Process.myUid()/100000)throw new SecurityException("SystemUI owner");
        systemUiUid=info.uid;
        audio.onAppBorn=()->worker.post(this::sync);
    }

    public static boolean media(AudioAttributes attributes){
        int usage=attributes.getUsage();
        if(usage==AudioAttributes.USAGE_MEDIA||usage==AudioAttributes.USAGE_GAME)return true;
        if(usage!=AudioAttributes.USAGE_UNKNOWN)return false;
        try{return (Integer)AudioAttributes.class.getMethod("getVolumeControlStream").invoke(attributes)==3;}
        catch(ReflectiveOperationException|RuntimeException unavailable){return false;}
    }

    public static void install(XposedModule module,ClassLoader loader,Consumer<String> note){
        if(Build.VERSION.SDK_INT!=37||!"dada".equals(Build.DEVICE))return;
        try{
            Class<?> service=Class.forName("com.android.server.audio.AudioService",false,loader);
            Method ready=service.getDeclaredMethod("systemReady");
            Method phase=Class.forName("com.android.server.audio.AudioService$Lifecycle",false,loader)
                    .getDeclaredMethod("onBootPhase",int.class);
            var field=service.getDeclaredField("mContext");
            if(ready.getReturnType()!=void.class||phase.getReturnType()!=void.class||Modifier.isStatic(ready.getModifiers())
                    ||field.getType()!=Context.class||Modifier.isStatic(field.getModifiers()))throw new NoSuchMethodException("audio context");
            field.setAccessible(true);
            if(!module.deoptimize(phase))throw new IllegalStateException("audio boot caller deoptimize");
            WindowAudioGain.install(module,loader,note);
            WindowAudioGain gain=WindowAudioGain.installed();
            if(gain==null)return;
            module.hook(ready).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                try{
                    Context context=(Context)field.get(chain.getThisObject());
                    var thread=new HandlerThread("NativeComplements-AppVolume");thread.start();
                    var handler=new Handler(thread.getLooper());
                    var host=new AppVolumeHost(context,handler,gain);
                    context.getSystemService(AudioManager.class).registerAudioPlaybackCallback(
                            new AudioManager.AudioPlaybackCallback(){
                                @Override public void onPlaybackConfigChanged(java.util.List<AudioPlaybackConfiguration> configs){host.sync();}
                            },handler);
                    context.registerReceiver(new BroadcastReceiver(){
                        @Override public void onReceive(Context c,Intent intent){
                            setResultCode(host.receive(getSentFromUid(),intent)?1:0);
                        }
                    },new IntentFilter(ACTION),null,handler,Context.RECEIVER_EXPORTED);
                    note.accept("APP_VOLUME_GAIN_CONNECTED");
                }catch(Exception failure){note.accept("APP_VOLUME_GAIN_CONNECT_FAILED "+failure.getClass().getSimpleName());}
                return result;
            });
        }catch(Exception failure){note.accept("APP_VOLUME_GAIN_UNAVAILABLE "+failure.getClass().getSimpleName());}
    }

    boolean receive(int sender,Intent intent){
        if(sender!=systemUiUid||!ACTION.equals(intent.getAction()))return false;
        try{
            Bundle extras=intent.getExtras();
            IBinder token=extras==null?null:extras.getBinder("owner");
            if(token==null||!token.isBinderAlive())return false;
            boolean reset=intent.getBooleanExtra("reset",false);
            int uid=intent.getIntExtra("uid",-1),level=intent.getIntExtra("level",-1);
            String pkg=intent.getStringExtra("package");
            if(!reset&&(level<0||level>100||uid<10000||uid/100000!=Process.myUid()/100000
                    ||!owns(uid,pkg)||!rules.containsKey(uid)&&rules.size()>=512))return false;
            if(!token.equals(owner)){
                clearOwner();
                death=()->worker.post(()->{if(token.equals(owner))clearOwner();});
                token.linkToDeath(death,0);owner=token;
            }
            if(reset)rules.clear();
            else if(level==100)rules.remove(uid);
            else rules.put(uid,new Rule(pkg,level));
            return sync();
        }catch(Exception failure){return false;}
    }

    private boolean owns(int uid,String pkg){
        String[] names=context.getPackageManager().getPackagesForUid(uid);
        return pkg!=null&&names!=null&&names.length==1&&pkg.equals(names[0]);
    }

    private void clearOwner(){
        if(owner!=null){owner.unlinkToDeath(death,0);owner=null;}
        rules.clear();sync();
    }

    private boolean sync(){
        worker.removeCallbacks(retry);
        boolean complete=true;
        java.util.List<AudioPlaybackConfiguration> configs;
        try{configs=manager.getActivePlaybackConfigurations();if(configs==null)throw new IllegalStateException("audio snapshot");}
        catch(RuntimeException unavailable){worker.postDelayed(retry,1000);return false;}
        for(AudioPlaybackConfiguration config:configs)try{
            if(!audio.isAAudio(config))continue;
            int uid=(Integer)AudioPlaybackConfiguration.class.getMethod("getClientUid").invoke(config);
            Rule rule=rules.get(uid);
            float level=rule!=null&&owns(uid,rule.packageName())&&media(config.getAudioAttributes())?rule.level()/100f:1f;
            if(!audio.known(config)){if(level!=1f)complete=false;continue;}
            audio.appGain(config,level);
        }catch(Throwable failure){complete=false;}
        if(!complete)worker.postDelayed(retry,1000);
        return complete;
    }

    public static void send(Context context,Handler handler,IBinder owner,int uid,String pkg,int level,
                            boolean reset,Consumer<Boolean> complete){
        Bundle extras=new Bundle();extras.putBinder("owner",owner);
        Intent request=new Intent(ACTION).setPackage("android").putExtras(extras)
                .putExtra("uid",uid).putExtra("package",pkg).putExtra("level",level).putExtra("reset",reset);
        try{
            context.sendOrderedBroadcast(request,null,BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
                    new BroadcastReceiver(){
                        @Override public void onReceive(Context c,Intent result){complete.accept(getResultCode()==1);}
                    },handler,0,null,null);
        }catch(RuntimeException failure){Log.w("PixelOSMediaVolume","AAudio request failed",failure);complete.accept(false);}
    }
}
