package com.hcn.autoradio;

import com.hcn.autoradio.IRadioCallBack;

interface IRadioServiceAPI {
    void registerRadioClientBinder(IBinder binder);
    void unRegisterRadioClientBinder();
    void registerRadioCallback(IRadioCallBack callback);
    void unRegisterRadioCallback(IRadioCallBack callback);
    void onBandEvent();
    void onASEvent();
    void onPSEvent();
    void onLocDxEvent();
    void onSeekDownEvent();
    void onSeekUpEvent();
    void onManualUpEvent();
    void onManualDownEvent();
    void onScanEvent();
    void gotoFreq(int frequency);
    void gotoFreq2(String frequency);
    void gotoFreqIndex(int index);
    void favoriteCurrentFreq();
    int getCurrentBand();
    int getCurrentFreq();
    String getCurrentFreqRdsPs();
    boolean getFreqIsFavorite(int band, int frequency);
    boolean currentFreqIsFavorite();
    boolean IsAS();
    boolean IsPS();
    boolean IsScan();
    boolean IsSeek();
    boolean IsStereo();
    boolean IsDxLocal();
    boolean requestPlayAudio();
    void requestAudioFocus();
    void releaseAudioFocus();
}
