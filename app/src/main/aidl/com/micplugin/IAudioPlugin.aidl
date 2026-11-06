package com.micplugin;

interface IAudioPlugin {
    String getPluginInfo();
    boolean processBuffer(inout float[] buffer, int frames, int sampleRate);
    void setParameter(int id, float value);
    float getParameter(int id);
    void onStart(int sampleRate, int framesPerBuffer);
    void onStop();
    String getEditorClassName();
}
