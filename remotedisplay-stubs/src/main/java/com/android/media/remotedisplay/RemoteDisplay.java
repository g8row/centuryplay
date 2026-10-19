package com.android.media.remotedisplay;

/** Stub, see build.gradle.kts. */
public class RemoteDisplay {
    public static final int STATUS_NOT_AVAILABLE = 0;
    public static final int STATUS_IN_USE = 1;
    public static final int STATUS_AVAILABLE = 2;
    public static final int STATUS_CONNECTING = 3;
    public static final int STATUS_CONNECTED = 4;
    public static final int PLAYBACK_VOLUME_VARIABLE = 1;
    public static final int PLAYBACK_VOLUME_FIXED = 0;

    public RemoteDisplay(String id, String name) { throw new RuntimeException("stub"); }
    public String getId() { throw new RuntimeException("stub"); }
    public String getName() { throw new RuntimeException("stub"); }
    public void setName(String name) { throw new RuntimeException("stub"); }
    public String getDescription() { throw new RuntimeException("stub"); }
    public void setDescription(String description) { throw new RuntimeException("stub"); }
    public int getStatus() { throw new RuntimeException("stub"); }
    public void setStatus(int status) { throw new RuntimeException("stub"); }
    public int getVolume() { throw new RuntimeException("stub"); }
    public void setVolume(int volume) { throw new RuntimeException("stub"); }
    public int getVolumeMax() { throw new RuntimeException("stub"); }
    public void setVolumeMax(int volumeMax) { throw new RuntimeException("stub"); }
    public int getVolumeHandling() { throw new RuntimeException("stub"); }
    public void setVolumeHandling(int volumeHandling) { throw new RuntimeException("stub"); }
    public int getPresentationDisplayId() { throw new RuntimeException("stub"); }
    public void setPresentationDisplayId(int presentationDisplayId) { throw new RuntimeException("stub"); }
}
