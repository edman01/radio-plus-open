package fi.radioplus.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Parked, native settings for the opt-in APK. Uses the same preferences, tuner, and routing as the radio. */
public final class CarSettingsActivity extends Activity implements RadioServiceClient.Listener {
    private enum Page {
        ROOT(R.string.action_setup), SENSITIVITY(R.string.settings_sensitivity), LANGUAGE(R.string.settings_language),
        WHEEL(R.string.settings_steering), TEST(R.string.steering_test_title), ABOUT(R.string.settings_about);
        final int title;
        Page(int title){ this.title=title; }
    }
    private static final int EXPORT_LOG=1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final int[] positions = new int[Page.values().length];
    private final SteeringDiagnosticTrace trace=SteeringDiagnosticTrace.get();
    private Page page=Page.ROOT;
    private CarSettingsViews ui;
    private RadioServiceClient radio;
    private Boolean localMode;
    private boolean started, connected, testOwned;
    private String originalLanguage;
    private Button sensitivityRow, testButton;
    private TextView testText;
    private int redraw;

    @Override protected void attachBaseContext(Context base){super.attachBaseContext(AppLanguage.wrap(base));}

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // In the mounted radio, keep the caller's landscape orientation. If opened by the
        // automotive host, keep its portrait or landscape orientation instead.
        ComponentName caller=getCallingActivity();
        if(caller!=null && getPackageName().equals(caller.getPackageName())
                && MainActivity.class.getName().equals(caller.getClassName()))
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_BEHIND);
        SupportTestLog.init(this);
        originalLanguage=AppLanguage.get(this);
        if(saved!=null) {
            int index=saved.getInt("page");
            if(index>=0 && index<Page.values().length)page=Page.values()[index];
            int[] restored=saved.getIntArray("scroll");
            if(restored!=null && restored.length==positions.length)System.arraycopy(restored,0,positions,0,positions.length);
            originalLanguage=saved.getString("originalLanguage",originalLanguage);
        }
        ui=new CarSettingsViews(this,this::up);
        ui.body.setSaveFromParentEnabled(false);
        radio=new RadioServiceClient(this,this);
        draw();
    }

    @Override protected void onStart(){super.onStart();started=true;radio.bind();if(page==Page.TEST)updateTest();}
    @Override protected void onStop(){
        started=false;radio.unbind();connected=false;localMode=null;
        handler.removeCallbacks(tick);clearTest();
        super.onStop();
    }
    @Override protected void onRestart(){super.onRestart();draw();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);radio.close();files.shutdown();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){
        positions[page.ordinal()]=ui.scroll.getScrollY();
        out.putInt("page",page.ordinal());out.putIntArray("scroll",positions);
        out.putString("originalLanguage",originalLanguage);super.onSaveInstanceState(out);
    }
    @Override public void onBackPressed(){up();}

    private void open(Page next){
        positions[page.ordinal()]=ui.scroll.getScrollY();page=next;positions[page.ordinal()]=0;
        if(page==Page.TEST){trace.clear();testOwned=true;}
        draw();
        if(!ui.root.isInTouchMode())ui.nav.requestFocus();
    }
    private void up(){
        if(page==Page.ROOT){
            setResult(originalLanguage.equals(AppLanguage.get(this))?RESULT_CANCELED:RESULT_OK);finish();return;
        }
        Page child=page;
        if(page==Page.TEST){clearTest();page=Page.WHEEL;}else page=Page.ROOT;
        draw();
        int id=child==Page.SENSITIVITY?R.id.settings_sensitivity:child==Page.LANGUAGE?R.id.settings_language:
                child==Page.ABOUT?R.id.settings_about:child==Page.WHEEL?R.id.settings_steering:R.id.car_test_open;
        View opener=ui.body.findViewById(id);
        if(opener!=null && !ui.root.isInTouchMode())opener.requestFocus();
    }
    private void draw(){
        final int generation=++redraw;
        handler.removeCallbacks(tick);ui.body.removeAllViews();
        testText=null;testButton=null;sensitivityRow=null;
        ui.header(page.title,page==Page.ROOT);
        switch(page){
            case ROOT:
                ui.toggle(R.id.settings_auto_start,s(R.string.settings_auto_start),s(R.string.settings_auto_start_hint),
                        AutoStartPreferences.isEnabled(this),(button,value)->{
                            AutoStartPreferences.setEnabled(this,value);
                            if(value && AutoStartNotice.messageId(true,android.os.Build.MANUFACTURER,
                                    android.os.Build.BRAND,android.os.Build.MODEL)==R.string.settings_auto_start_enabled_junsun)
                                toast(R.string.settings_auto_start_enabled_junsun);
                        });
                sensitivityRow=entry(R.id.settings_sensitivity,Page.SENSITIVITY,sensitivityValue());
                updateSensitivityRow();
                ui.section(R.string.settings_general);
                entry(R.id.settings_language,Page.LANGUAGE,AppLanguage.displayName(this));
                entry(R.id.settings_steering,Page.WHEEL,s(R.string.car_wheel_hint));
                entry(R.id.settings_about,Page.ABOUT,getString(R.string.about_version,BuildConfig.VERSION_NAME));
                break;
            case SENSITIVITY:
                if(localMode==null){
                    ui.section(R.string.settings_unknown);ui.description(s(R.string.car_tuner_hint));
                }else{
                    ui.choice(s(R.string.settings_local),localMode,()->setMode(true));
                    ui.choice(s(R.string.settings_dx),!localMode,()->setMode(false));
                }
                break;
            case LANGUAGE:
                ui.description(s(R.string.car_locale_hint));
                String[] names=AppLanguage.names(),codes=AppLanguage.codes();
                for(int i=0;i<codes.length;i++){
                    final String code=codes[i];ui.choice(names[i],code.equals(AppLanguage.get(this)),()->setLanguage(code));
                }
                break;
            case WHEEL: wheel();break;
            case TEST:
                ui.description(s(R.string.car_test_hint));
                testButton=ui.action(View.NO_ID,s(R.string.steering_test_start),null,false,()->{
                    if(trace.isRecording())trace.stop();else {trace.begin();testOwned=true;}
                    updateTest();
                });
                testText=ui.description(""); updateTest();break;
            case ABOUT:
                ui.section(R.string.about_title);
                ui.description(getString(R.string.about_version,BuildConfig.VERSION_NAME));
                ui.description(s(connected ? R.string.message_103 : R.string.car_no_tuner));
                if(BuildConfig.REGLINK_STOCK_ASSISTED)ui.action(View.NO_ID,s(R.string.support_log_export),
                        s(R.string.car_log_hint),false,this::chooseLogFile);
                // The aftermarket APK retains the project link. Do not launch a web browser from an AAOS media surface.
                if(!getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
                    ui.action(View.NO_ID,s(R.string.about_open_github),null,false,()->
                            launchExternal(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/edman01/radio-plus-open")),R.string.about_browser_unavailable));
                break;
        }
        final int y=positions[page.ordinal()];
        ui.scroll.post(()->{if(generation==redraw)ui.scroll.scrollTo(0,y);});
    }
    private Button entry(int id,Page target,String summary){
        return ui.action(id,s(target.title),summary,true,()->open(target));
    }
    private String s(int id){return getString(id);}
    private String sensitivityValue(){return s(localMode==null?R.string.settings_unknown:localMode?R.string.settings_local:R.string.settings_dx);}
    private void updateSensitivityRow(){
        if(sensitivityRow==null)return;
        sensitivityRow.setText(ui.caption(s(R.string.settings_sensitivity),sensitivityValue()));
        sensitivityRow.setCompoundDrawablesRelativeWithIntrinsicBounds(0,0,localMode==null?R.drawable.car_info:R.drawable.car_next,0);
    }
    private void capability(Boolean value){
        if(Objects.equals(localMode,value))return;
        localMode=value;
        updateSensitivityRow();
        if(page==Page.SENSITIVITY)draw();
    }
    @Override public void onConnectionChanged(boolean yes,String message){
        if(!started)return;
        boolean changed=connected!=yes; connected=yes;if(!yes)capability(null);
        if(changed && (page==Page.WHEEL || page==Page.ABOUT)){positions[page.ordinal()]=ui.scroll.getScrollY();draw();}
    }
    @Override public void onStateChanged(RadioServiceClient.RadioState state){
        if(started)capability(connected && RadioApiFactory.selectedSupportsLocalMode()?state.localMode:null);
    }
    @Override public void onRadioError(String message){
        if(started && page==Page.SENSITIVITY && connected && localMode!=null)toast(R.string.message_109);
    }
    private void setMode(boolean desired){
        if(!connected||localMode==null||!radio.isConnected()||!RadioApiFactory.selectedSupportsLocalMode())return;
        // The vendor API is a toggle, not a setter. Re-read on its serial queue so a double tap
        // or an external change cannot invert the setting twice. Render only confirmed tuner state.
        radio.perform(remote->{if(remote.IsDxLocal()!=desired)remote.onLocDxEvent();});
        up();
    }
    private void setLanguage(String code){
        if(!AppLanguage.set(this,code)){up();return;}
        StationWidgetProvider.updateAll(this);RadioPlaybackService.refreshLocalizedText();
        page=Page.ROOT;ui.scroll.scrollTo(0,positions[Page.ROOT.ordinal()]);recreate();
    }
    private void wheel(){
        if(BuildConfig.REGLINK_STOCK_ASSISTED && RadioApiFactory.usesS602LauncherLists()){
            String key="reglink_s602_4754_wheel_list";
            android.content.SharedPreferences prefs=getSharedPreferences("radio_plus_navigation",MODE_PRIVATE);
            boolean list=prefs.getBoolean(key,false);
            for(int i=0;i<2;i++){
                boolean enabled=i==1;
                ui.choice(s(enabled?R.string.reglink_wheel_list:R.string.reglink_wheel_seek),list==enabled,()->{
                    prefs.edit().putBoolean(key,enabled).apply();
                    SupportTestLog.event(SupportTestLog.Event.WHEEL_LISTENER,enabled?2:0,0);up();
                });
            }
            return;
        }
        if(RadioPlaybackService.shouldIgnoreRawMediaKeys()){
            ui.description(s(R.string.settings_unknown));return;
        }
        if(connected && RadioPlaybackService.supportsWheelRouteRepair())
            ui.action(View.NO_ID,s(R.string.steering_restore_route),null,false,()->toast(
                    RadioPlaybackService.requestWheelRouteRepair()?R.string.steering_restore_requested:R.string.steering_restore_unavailable));
        entry(R.id.car_test_open,Page.TEST,null);
        ui.action(View.NO_ID,s(R.string.steering_open_settings),s(R.string.car_access_hint),true,()->
                launchExternal(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),R.string.steering_settings_unavailable));
    }
    private final Runnable tick=this::updateTest;
    private void updateTest(){
        handler.removeCallbacks(tick);if(page!=Page.TEST || testButton==null)return;
        boolean recording=trace.isRecording();
        testButton.setText(recording?s(R.string.message_100):s(R.string.steering_test_start));
        if(testText!=null)testText.setText(testOwned?
                (recording?s(R.string.steering_test_recording)+" · "+trace.remainingSeconds()+" s":s(R.string.steering_test_stopped))
                        +"\n"+trace.report():s(R.string.steering_test_stopped));
        if(recording && started)handler.postDelayed(tick,500);
    }
    private void clearTest(){handler.removeCallbacks(tick);if(testOwned)trace.clear();testOwned=false;}
    @Override public void onWindowFocusChanged(boolean focus){
        super.onWindowFocusChanged(focus);if(!focus && page==Page.TEST && testOwned){trace.stop();updateTest();}
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(page==Page.TEST && testOwned && trace.isRecording() && RadioPlaybackService.supportsMediaKey(event.getKeyCode())){
            trace.key("settings-test",event.getKeyCode(),event.getAction(),event.getMetaState(),event.getRepeatCount(),
                    event.getScanCode(),event.getFlags(),event.getSource(),event.getDownTime(),event.getEventTime());
            if(connected && RadioMediaButtonReceiver.dispatch(this,event))return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private void chooseLogFile(){
        try{
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain").putExtra(Intent.EXTRA_TITLE,"RadioPlus-"+BuildConfig.VERSION_NAME+".txt"),EXPORT_LOG);
        }catch(ActivityNotFoundException|SecurityException e){toast(R.string.support_log_picker_unavailable);}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=EXPORT_LOG || result!=RESULT_OK || data==null || data.getData()==null)return;
        Uri dest=data.getData();
        if(!"content".equals(dest.getScheme()))return;
        files.execute(()->{
            boolean ok=false;
            try(OutputStream out=getContentResolver().openOutputStream(dest,"wt")){
                if(out!=null){out.write(SupportTestLog.exportSnapshot().getBytes(StandardCharsets.UTF_8));ok=true;}
            }catch(java.io.IOException|RuntimeException ignored){}
            final int msg=ok?R.string.support_log_saved:R.string.support_log_save_failed;
            handler.post(()->{if(!isDestroyed()&&!isFinishing())toast(msg);});
        });
    }
    private void launchExternal(Intent intent,int error){
        try{startActivity(intent);}catch(ActivityNotFoundException|SecurityException unavailable){toast(error);}
    }
    private void toast(int id){Toast.makeText(this,id,Toast.LENGTH_SHORT).show();}
}
