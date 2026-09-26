package com.airplay.streamer.shizuku;

// Runs as the shell user inside a Shizuku UserService process.
interface IPrivilegedService {
    // Shizuku calls this transaction code when the service should exit.
    void destroy() = 16777114;

    // Run a shell command as the shell user; returns "exitCode\nstdout+stderr".
    String exec(String command) = 1;

    // Start capturing media playback through an AudioPolicy loopback mix and return the
    // read end of a pipe carrying 44.1 kHz 16-bit stereo little-endian PCM.
    // keepLocalPlayback=false reroutes the audio (the phone goes silent).
    ParcelFileDescriptor startCapture(boolean keepLocalPlayback) = 2;

    void stopCapture() = 3;

    int version() = 4;

    // Like startCapture, but only for the given AudioAttributes usages.
    ParcelFileDescriptor startCaptureForUsages(boolean keepLocalPlayback, in int[] usages) = 5;
}
