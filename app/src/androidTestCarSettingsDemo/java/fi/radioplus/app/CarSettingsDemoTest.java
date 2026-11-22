package fi.radioplus.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import static org.junit.Assert.*;

/** Run on a stock emulator, with no virtual tuner, fabricated stations, or fake preferences. */
public final class CarSettingsDemoTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context ctx=inst.getTargetContext();
    private String oldLanguage;
    private boolean oldAuto;

    @Before public void setup(){ oldLanguage=AppLanguage.get(ctx);oldAuto=AutoStartPreferences.isEnabled(ctx);AppLanguage.set(ctx,"fi"); }
    @After public void cleanup() throws Exception {
        main(()->{
            for(Stage stage:new Stage[]{Stage.RESUMED,Stage.PAUSED,Stage.STOPPED}){
                for(Activity a:new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)))
                    if(!a.isFinishing() && !a.isDestroyed())a.finish();
            }
            return null;
        });
        inst.waitForIdleSync();AppLanguage.set(ctx,oldLanguage);AutoStartPreferences.setEnabled(ctx,oldAuto);
    }
    private <T>T main(Callable<T> work)throws Exception{
        FutureTask<T> task=new FutureTask<>(work);inst.runOnMainSync(task);return task.get();
    }
    private <T extends Activity>T resumed(Class<T> type)throws Exception{
        long end=SystemClock.uptimeMillis()+6500;
        do{
            T a=main(()->{for(Activity v:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                if(type.isInstance(v)&&!v.isFinishing()&&!v.isDestroyed())return type.cast(v);return null;});
            if(a!=null)return a;SystemClock.sleep(40);
        }while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("Not resumed: "+type);
    }
    private CarSettingsActivity launch()throws Exception{
        inst.startActivitySync(new Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(ctx.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        inst.waitForIdleSync();return resumed(CarSettingsActivity.class);
    }
    private void tap(Activity a,int id)throws Exception{
        main(()->{
            View v=a.findViewById(id);assertNotNull(v);assertTrue(v.isEnabled()&&v.isClickable());
            // CompoundButton can toggle and notify its checked listener even when performClick()
            // returns false (there is no separate OnClickListener). Assert the outcome in the test.
            v.performClick();return null;
        });
        inst.waitForIdleSync();
    }
    private String title(CarSettingsActivity a)throws Exception{return main(()->((TextView)a.findViewById(R.id.car_settings_title)).getText().toString());}
    private void verifyTree(View v,float d){
        assertFalse("Settings must not be HTML",v instanceof WebView);
        if(v instanceof TextView){
            TextView t=(TextView)v;
            assertTrue("Type too small: "+t.getText(),t.getTextSize()+1>=(v.isClickable()?32:24)*d);
            if(t.getText() instanceof Spanned){
                Spanned s=(Spanned)t.getText();
                for(AbsoluteSizeSpan span:s.getSpans(0,s.length(),AbsoluteSizeSpan.class))assertTrue(span.getSize()+1>=24*d);
            }
            if(t.getLayout()!=null)for(int i=0;i<t.getLayout().getLineCount();i++)assertEquals("Clipped label",0,t.getLayout().getEllipsisCount(i));
        }
        if(v.isClickable()){
            assertTrue("Non-native preference",v instanceof Button);
            assertTrue("Short control: "+v,v.getHeight()+1>=76*d);assertTrue("Narrow control",v.getWidth()+1>=76*d);assertTrue(v.isFocusable());
        }
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)verifyTree(g.getChildAt(i),d);}
    }
    private void inspect(CarSettingsActivity a,boolean root)throws Exception{
        inst.waitForIdleSync();
        main(()->{
            float d=a.getResources().getDisplayMetrics().density;
            Button nav=a.findViewById(R.id.settings_close);assertEquals(root?"Sulje":"Takaisin",nav.getContentDescription().toString());
            TextView title=a.findViewById(R.id.car_settings_title);
            int[] ni=new int[2],ti=new int[2];nav.getLocationOnScreen(ni);title.getLocationOnScreen(ti);
            assertTrue("Navigation must precede title",ni[0]+nav.getWidth()<=ti[0]);
            verifyTree(a.findViewById(android.R.id.content),d);
            LinearLayout body=a.findViewById(R.id.car_settings_body);
            assertTrue(body.getChildCount()>0);
            int edge=-1;
            for(int i=0;i<body.getChildCount();i++){
                View r=body.getChildAt(i);
                assertTrue("Content exceeds width",r.getRight()<=body.getWidth());
                if(r.isClickable()){if(edge>=0)assertTrue("Gap below 24dp",r.getTop()-edge+1>=24*d);edge=r.getBottom();}
            }
            return null;
        });
    }
    private static Bitmap rendered(Drawable icon) {
        Rect original=icon.copyBounds();
        Bitmap b=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888);
        try {icon.setBounds(0,0,b.getWidth(),b.getHeight());icon.draw(new Canvas(b));}
        finally {icon.setBounds(original);}
        return b;
    }

    private void assertFrameworkNav(CarSettingsActivity a,boolean root)throws Exception {
        main(()->{
            Button nav=a.findViewById(R.id.settings_close);
            int attr=root?android.R.attr.closeIcon:android.R.attr.homeAsUpIndicator;
            TypedArray attrs=a.obtainStyledAttributes(null,new int[]{attr},root?android.R.attr.searchViewStyle:0,0);
            Drawable original;
            try {original=attrs.getDrawable(0);}finally {attrs.recycle();}
            assertNotNull("Stock Android navigation icon",original);
            original=original.mutate();
            original.setTint(nav.getCurrentTextColor());
            original.setLayoutDirection(nav.getLayoutDirection());
            Drawable actual=nav.getCompoundDrawablesRelative()[0];
            assertNotNull(actual);
            assertTrue("Use the actual themed Android glyph",rendered(original).sameAs(rendered(actual)));
            return null;
        });
    }

    @Test public void headerHasNoTrailingLogoAndKeepsBorderlessPlatformNavigation() throws Exception {
        CarSettingsActivity a=launch();
        main(()->{
            Button nav=a.findViewById(R.id.settings_close);
            ViewGroup bar=(ViewGroup)nav.getParent();
            assertSame("No logo or reserved slot after the title",a.findViewById(R.id.car_settings_title),
                    bar.getChildAt(bar.getChildCount()-1));
            assertTrue("Focus and press feedback",nav.getBackground().isStateful());
            Drawable idle=nav.getBackground().getConstantState().newDrawable(a.getResources(),a.getTheme()).mutate();
            idle.setState(new int[]{android.R.attr.state_enabled});idle.jumpToCurrentState();
            assertTrue("No resting button box",Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888).sameAs(rendered(idle)));
            return null;
        });
        assertFrameworkNav(a,true);tap(a,R.id.settings_language);assertFrameworkNav(a,false);
    }

    @Test public void exportedParkedActivityHasNoDistractionExemption() throws Exception{
        Intent intent=new Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(ctx.getPackageName());
        ActivityInfo info=ctx.getPackageManager().resolveActivity(intent,PackageManager.GET_META_DATA).activityInfo;
        assertEquals(CarSettingsActivity.class.getName(),info.name);assertTrue(info.exported);
        assertTrue(info.metaData==null || !info.metaData.getBoolean("distractionOptimized"));
        assertEquals("fi.radioplus.app.play.settingsdemo",ctx.getPackageName());
    }
    @Test public void preferencePersistsAndUnavailableTunerHasNoFakeChoice() throws Exception{
        CarSettingsActivity first=launch();
        tap(first,R.id.settings_auto_start);assertEquals(!oldAuto,AutoStartPreferences.isEnabled(ctx));
        tap(first,R.id.settings_sensitivity);
        assertEquals("Hakuherkkyys",title(first));
        main(()->{
            LinearLayout body=first.findViewById(R.id.car_settings_body);
            for(int i=0;i<body.getChildCount();i++)assertFalse(body.getChildAt(i) instanceof RadioButton);
            assertTrue(((TextView)body.getChildAt(1)).getText().toString().contains("LOCAL/DX"));return null;
        });
        tap(first,R.id.settings_close);assertEquals("Asetukset",title(first));tap(first,R.id.settings_close);
        assertTrue(first.isFinishing()||first.isDestroyed());
        CarSettingsActivity next=launch();
        assertEquals(!oldAuto,main(()->((Switch)next.findViewById(R.id.settings_auto_start)).isChecked()));
    }
    @Test public void actualRadioOpensSettingsAndLanguageSurvivesRecreation() throws Exception{
        MainActivity radio=(MainActivity)inst.startActivitySync(new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        tap(radio,R.id.more_button);CarSettingsActivity settings=resumed(CarSettingsActivity.class);
        tap(settings,R.id.settings_language);
        main(()->{
            LinearLayout body=settings.findViewById(R.id.car_settings_body);
            for(int i=0;i<body.getChildCount();i++)if(body.getChildAt(i) instanceof RadioButton){
                RadioButton b=(RadioButton)body.getChildAt(i);if("English".contentEquals(b.getText())){b.performClick();return null;}
            }
            throw new AssertionError("Missing language");
        });
        inst.waitForIdleSync();
        CarSettingsActivity english=resumed(CarSettingsActivity.class);
        assertEquals("en",AppLanguage.get(ctx));assertEquals("Settings",title(english));
        tap(english,R.id.settings_close);MainActivity back=resumed(MainActivity.class);
        assertEquals("Settings",main(()->back.getString(R.string.action_setup)));
    }
    @Test public void controlsMeetCarDimensionsInPortraitAndLandscape() throws Exception{
        CarSettingsActivity a=launch();
        for(int orientation:new int[]{ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}){
            final CarSettingsActivity before=a;
            main(()->{before.setRequestedOrientation(orientation);return null;});
            int expected=orientation==ActivityInfo.SCREEN_ORIENTATION_PORTRAIT?Configuration.ORIENTATION_PORTRAIT:Configuration.ORIENTATION_LANDSCAPE;
            long end=SystemClock.uptimeMillis()+6000;
            do{SystemClock.sleep(70);a=resumed(CarSettingsActivity.class);}
            while(a.getResources().getConfiguration().orientation!=expected && SystemClock.uptimeMillis()<end);
            assertEquals(expected,a.getResources().getConfiguration().orientation);
            inspect(a,true);
            for(int id:new int[]{R.id.settings_sensitivity,R.id.settings_language,R.id.settings_steering,R.id.settings_about}){
                tap(a,id);inspect(a,false);
                if(id==R.id.settings_steering){tap(a,R.id.car_test_open);inspect(a,false);tap(a,R.id.settings_close);}
                tap(a,R.id.settings_close);
            }
        }
    }
    @Test public void androidBackReturnsOneLevelAndCloseReturnsToRadio() throws Exception{
        MainActivity radio=(MainActivity)inst.startActivitySync(new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        tap(radio,R.id.more_button);CarSettingsActivity a=resumed(CarSettingsActivity.class);
        tap(a,R.id.settings_steering);tap(a,R.id.car_test_open);
        inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);inst.waitForIdleSync();
        assertEquals("Rattipainikkeet",title(a));
        inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);inst.waitForIdleSync();
        assertEquals("Asetukset",title(a));
        tap(a,R.id.settings_close);assertNotNull(resumed(MainActivity.class));
    }
    @Test public void textAndIndicatorsExceedRequiredContrastIncludingPress(){
        int pressed=CarSettingsViews.PRESSED;
        int lit=Color.rgb(lighten(Color.red(pressed)),lighten(Color.green(pressed)),lighten(Color.blue(pressed)));
        for(int bg:new int[]{CarSettingsViews.BASE,CarSettingsViews.SURFACE,pressed,lit})
            for(int fg:new int[]{CarSettingsViews.PRIMARY,CarSettingsViews.SECONDARY,CarSettingsViews.INDICATOR,ctx.getColor(R.color.accent)})
                assertTrue("Contrast "+Integer.toHexString(fg)+"/"+Integer.toHexString(bg),(Color.luminance(fg)+.05)/(Color.luminance(bg)+.05)>=4.5);
    }
    private static int lighten(int component){return Math.round(component+(255-component)*(24/255f));}
}
