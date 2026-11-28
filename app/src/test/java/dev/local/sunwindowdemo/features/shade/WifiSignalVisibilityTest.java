package dev.local.sunwindowdemo.features.shade;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class WifiSignalVisibilityTest {
    public interface Displayable { String getSlot(); boolean isIconVisible(); boolean isIconBlocked(); }
    public static class Icon extends View implements Displayable {
        boolean blocked;
        Icon(Context context){super(context);}
        public String getSlot(){return "wifi";}
        public boolean isIconVisible(){return true;}
        public boolean isIconBlocked(){return blocked;}
    }
    public static class Container extends FrameLayout {
        public final Set<String> mIgnoredSlots=new HashSet<>();
        Container(Context context){super(context);}
    }
    private Container container;
    private Icon wifi;
    @Before public void setUp()throws Exception{
        var context=RuntimeEnvironment.getApplication();container=new Container(context);wifi=new Icon(context);container.addView(wifi);
        ReflectionHelpers.setStaticField(WifiSignalVisibility.class,"displayable",Displayable.class);
        ReflectionHelpers.setStaticField(WifiSignalVisibility.class,"ignoredSlots",Container.class.getField("mIgnoredSlots"));
        ReflectionHelpers.setStaticField(WifiSignalVisibility.class,"getSlot",Displayable.class.getMethod("getSlot"));
        ReflectionHelpers.setStaticField(WifiSignalVisibility.class,"isIconVisible",Displayable.class.getMethod("isIconVisible"));
        ReflectionHelpers.setStaticField(WifiSignalVisibility.class,"isIconBlocked",Displayable.class.getMethod("isIconBlocked"));
        ReflectionHelpers.setStaticField(WifiSignalVisibility.class,"available",true);
        WifiSignalVisibility.setEnabled(true);
    }
    private void layout(Supplier<Object> nativeLayout)throws Throwable{
        var chain=(XposedInterface.Chain)Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class},(p,m,a)->switch(m.getName()){
                    case "getThisObject"->container;case "proceed"->nativeLayout.get();default->null;
                });
        var method=WifiSignalVisibility.class.getDeclaredMethod("applyIconPolicy",XposedInterface.Chain.class);method.setAccessible(true);
        try{method.invoke(null,chain);}catch(InvocationTargetException failure){throw failure.getCause();}
    }
    @Test public void visibleWifiTemporarilyHidesBothCellularSlotsThenRestores()throws Throwable{
        layout(()->{assertTrue(container.mIgnoredSlots.containsAll(Set.of("mobile","stacked_mobile")));return null;});
        assertTrue(container.mIgnoredSlots.isEmpty());
        wifi.setVisibility(View.GONE);
        layout(()->{assertTrue(container.mIgnoredSlots.isEmpty());return null;});
        wifi.setVisibility(View.VISIBLE);WifiSignalVisibility.setEnabled(false);
        layout(()->{assertTrue(container.mIgnoredSlots.isEmpty());return null;});
    }
    @Test public void blockedOrSystemIgnoredWifiDoesNotHideCellular()throws Throwable{
        wifi.blocked=true;layout(()->{assertTrue(container.mIgnoredSlots.isEmpty());return null;});
        wifi.blocked=false;container.mIgnoredSlots.add("wifi");
        layout(()->{assertEquals(Set.of("wifi"),container.mIgnoredSlots);return null;});
    }
    @Test public void nativeFailureRestoresOnlyModuleChanges()throws Throwable{
        container.mIgnoredSlots.add("mobile");
        try{layout(()->{throw new IllegalStateException("native layout");});fail();}
        catch(IllegalStateException expected){assertEquals("native layout",expected.getMessage());}
        assertEquals(Set.of("mobile"),container.mIgnoredSlots);
    }
}
