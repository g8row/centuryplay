// JNI bridge to Apple's reference ALAC encoder/decoder for RAOP streaming.
#include <jni.h>
#include <cstring>
#include <new>
#include "ALACEncoder.h"
#include "ALACDecoder.h"
#include "ALACBitUtilities.h"
#include "EndianPortable.h"

namespace {

struct Encoder {
    ALACEncoder enc;
    AudioFormatDescription input{};
    AudioFormatDescription output{};
    int channels = 2;
};

AudioFormatDescription pcmFormat(int sampleRate, int channels) {
    AudioFormatDescription f{};
    f.mSampleRate = sampleRate;
    f.mFormatID = kALACFormatLinearPCM;
    f.mFormatFlags = kALACFormatFlagIsSignedInteger | kALACFormatFlagIsPacked; // native (little) endian
    f.mBytesPerPacket = 2 * channels;
    f.mFramesPerPacket = 1;
    f.mBytesPerFrame = 2 * channels;
    f.mChannelsPerFrame = channels;
    f.mBitsPerChannel = 16;
    return f;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_airplay_streamer_audio_AlacNative_nativeCreateEncoder(JNIEnv*, jclass, jint sampleRate,
                                                              jint channels, jint frameSize, jboolean fast) {
    auto* e = new (std::nothrow) Encoder();
    if (!e) return 0;
    e->channels = channels;
    e->input = pcmFormat(sampleRate, channels);
    e->output.mSampleRate = sampleRate;
    e->output.mFormatID = kALACFormatAppleLossless;
    e->output.mFormatFlags = 1; // 16-bit source data
    e->output.mFramesPerPacket = frameSize;
    e->output.mChannelsPerFrame = channels;
    e->enc.SetFastMode(fast);
    e->enc.SetFrameSize(frameSize);
    if (e->enc.InitializeEncoder(e->output) != 0) {
        delete e;
        return 0;
    }
    return reinterpret_cast<jlong>(e);
}

// Encodes `frames` interleaved little-endian 16-bit frames from pcm[offset..] into out.
// Returns the number of bytes written, or -1 on error.
JNIEXPORT jint JNICALL
Java_com_airplay_streamer_audio_AlacNative_nativeEncode(JNIEnv* env, jclass, jlong handle, jbyteArray pcm,
                                                       jint offset, jint frames, jbyteArray out) {
    auto* e = reinterpret_cast<Encoder*>(handle);
    if (!e) return -1;
    const int inBytes = frames * 2 * e->channels;
    if (env->GetArrayLength(pcm) < offset + inBytes) return -1;
    const int outCap = env->GetArrayLength(out);
    if (outCap < inBytes + kALACMaxEscapeHeaderBytes + 16) return -1;

    auto* in = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(pcm, nullptr));
    auto* dst = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(out, nullptr));
    int32_t numBytes = inBytes;
    int32_t status = e->enc.Encode(e->input, e->input, reinterpret_cast<unsigned char*>(in + offset),
                                   reinterpret_cast<unsigned char*>(dst), &numBytes);
    env->ReleasePrimitiveArrayCritical(out, dst, 0);
    env->ReleasePrimitiveArrayCritical(pcm, in, JNI_ABORT);
    return status == 0 ? numBytes : -1;
}

JNIEXPORT void JNICALL
Java_com_airplay_streamer_audio_AlacNative_nativeDestroyEncoder(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Encoder*>(handle);
}

// Decodes one ALAC packet (used by self-tests). Returns decoded frames, or -1.
JNIEXPORT jint JNICALL
Java_com_airplay_streamer_audio_AlacNative_nativeDecode(JNIEnv* env, jclass, jbyteArray packet, jint length,
                                                       jint frameSize, jint channels, jbyteArray outPcm) {
    // Standard RAOP magic cookie: 352 0 16 40 10 14 2 255 0 0 44100
    ALACSpecificConfig cfg{};
    cfg.frameLength = Swap32NtoB(frameSize);
    cfg.compatibleVersion = 0;
    cfg.bitDepth = 16;
    cfg.pb = 40;
    cfg.mb = 10;
    cfg.kb = 14;
    cfg.numChannels = channels;
    cfg.maxRun = Swap16NtoB(255);
    cfg.maxFrameBytes = 0;
    cfg.avgBitRate = 0;
    cfg.sampleRate = Swap32NtoB(44100);
    ALACDecoder dec;
    if (dec.Init(&cfg, sizeof(cfg)) != 0) return -1;

    auto* in = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(packet, nullptr));
    auto* dst = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(outPcm, nullptr));
    BitBuffer bits;
    BitBufferInit(&bits, reinterpret_cast<uint8_t*>(in), length);
    uint32_t outFrames = 0;
    int32_t status = dec.Decode(&bits, reinterpret_cast<uint8_t*>(dst), frameSize, channels, &outFrames);
    env->ReleasePrimitiveArrayCritical(outPcm, dst, 0);
    env->ReleasePrimitiveArrayCritical(packet, in, JNI_ABORT);
    return status == 0 ? static_cast<jint>(outFrames) : -1;
}

} // extern "C"
