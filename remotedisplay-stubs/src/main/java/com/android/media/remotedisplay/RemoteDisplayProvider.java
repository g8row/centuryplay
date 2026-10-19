package com.android.media.remotedisplay;

import android.app.PendingIntent;
import android.content.Context;
import android.os.IBinder;
import java.util.Collection;

/** Stub, see build.gradle.kts. */
public abstract class RemoteDisplayProvider {
    public static final String SERVICE_INTERFACE = "com.android.media.remotedisplay.RemoteDisplayProvider";
    public static final int DISCOVERY_MODE_NONE = 0;
    public static final int DISCOVERY_MODE_PASSIVE = 1;
    public static final int DISCOVERY_MODE_ACTIVE = 2;

    public RemoteDisplayProvider(Context context) { throw new RuntimeException("stub"); }
    public final Context getContext() { throw new RuntimeException("stub"); }
    public IBinder getBinder() { throw new RuntimeException("stub"); }
    public void onDiscoveryModeChanged(int mode) { throw new RuntimeException("stub"); }
    public void onConnect(RemoteDisplay display) { throw new RuntimeException("stub"); }
    public void onDisconnect(RemoteDisplay display) { throw new RuntimeException("stub"); }
    public void onSetVolume(RemoteDisplay display, int volume) { throw new RuntimeException("stub"); }
    public void onAdjustVolume(RemoteDisplay display, int delta) { throw new RuntimeException("stub"); }
    public int getDiscoveryMode() { throw new RuntimeException("stub"); }
    public Collection<RemoteDisplay> getDisplays() { throw new RuntimeException("stub"); }
    public void addDisplay(RemoteDisplay display) { throw new RuntimeException("stub"); }
    public void updateDisplay(RemoteDisplay display) { throw new RuntimeException("stub"); }
    public void removeDisplay(RemoteDisplay display) { throw new RuntimeException("stub"); }
    public RemoteDisplay findRemoteDisplay(String id) { throw new RuntimeException("stub"); }
    public PendingIntent getSettingsPendingIntent() { throw new RuntimeException("stub"); }
}
