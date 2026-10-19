package com.airplay.streamer.system

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.util.Log

/**
 * Media capture through an AudioPolicy loopback mix — the same technique scrcpy uses for
 * `--audio-source=playback` (scrcpy AudioPlaybackCapture.java, Apache 2.0). Needs
 * MODIFY_AUDIO_ROUTING, so it runs either in the Shizuku helper (shell uid) or in the app
 * itself when it is installed as a privileged system app ([SystemMode]).
 *
 * With [keepLocal] false the mix is LOOP_BACK only: matching audio is *rerouted* into our
 * AudioRecord and the phone speaker stays silent. Such mixes are trusted by the audio policy
 * (AudioPolicyMix only filters loopback+render mixes), so apps that limit capture to the
 * system (Apple Music) are captured too, at full quality.
 *
 * Everything here is reflection on @SystemApi classes: fine in app_process, and for a system
 * app that is on the hidden-API allowlist (sysconfig hidden-api-whitelisted-app).
 */
class PolicyCapture(private val context: Context) {

    private var policy: Any? = null

    @Synchronized
    fun start(keepLocal: Boolean, usages: IntArray): AudioRecord {
        stop()
        try {
            return createPolicyRecord(keepLocal, if (usages.isEmpty()) DEFAULT_USAGES else usages)
        } catch (e: Throwable) {
            stop()
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            Log.e(TAG, "audio policy capture failed", cause)
            throw IllegalStateException("audio policy capture failed: ${cause.javaClass.simpleName}: ${cause.message}", cause)
        }
    }

    @Synchronized
    fun stop() {
        policy?.let { unregisterPolicy(it) }
        policy = null
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun createPolicyRecord(keepLocal: Boolean, usages: IntArray): AudioRecord {
        val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
        ruleBuilderClass.getMethod("setTargetMixRole", Int::class.javaPrimitiveType)
            .invoke(ruleBuilder, ruleClass.getField("MIX_ROLE_PLAYERS").getInt(null))
        val matchUsage = ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
        val addRule = ruleBuilderClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
        for (usage in usages) {
            addRule.invoke(ruleBuilder, matchUsage, AudioAttributes.Builder().setUsage(usage).build())
        }
        // Note: allowPrivilegedPlaybackCapture(true) is only needed for loopback+render mixes and
        // AudioService only permits it for <=16 kHz mono (AudioMix.canBeUsedForPrivilegedMediaCapture).
        val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule)
        mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, FORMAT)
        val flagName = if (keepLocal) "ROUTE_FLAG_LOOP_BACK_RENDER" else "ROUTE_FLAG_LOOP_BACK"
        mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
            .invoke(mixBuilder, mixClass.getField(flagName).getInt(null))
        val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)

        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(context)
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
        val newPolicy = policyBuilderClass.getMethod("build").invoke(policyBuilder)!!

        val register = AudioManager::class.java.getDeclaredMethod("registerAudioPolicyStatic", policyClass)
        register.isAccessible = true
        val result = register.invoke(null, newPolicy) as Int
        if (result != 0) throw IllegalStateException("registerAudioPolicy returned $result")
        policy = newPolicy

        return policyClass.getMethod("createAudioRecordSink", mixClass).invoke(newPolicy, mix) as AudioRecord?
            ?: throw IllegalStateException("createAudioRecordSink returned null")
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
    private fun unregisterPolicy(p: Any) {
        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        try {
            val m = AudioManager::class.java.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policyClass)
            m.isAccessible = true
            m.invoke(null, p)
            return
        } catch (_: NoSuchMethodException) {
        } catch (e: Exception) {
            Log.w(TAG, "unregisterAudioPolicyAsyncStatic failed: ${e.message}")
        }
        try {
            // Fallback: IAudioService.unregisterAudioPolicyAsync(policy.cb())
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, Context.AUDIO_SERVICE)
            val stub = Class.forName("android.media.IAudioService\$Stub")
            val service = stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            val cb = policyClass.getDeclaredMethod("cb").apply { isAccessible = true }.invoke(p)
            val cbClass = Class.forName("android.media.audiopolicy.IAudioPolicyCallback")
            service!!.javaClass.getMethod("unregisterAudioPolicyAsync", cbClass).invoke(service, cb)
        } catch (e: Exception) {
            Log.w(TAG, "could not unregister audio policy: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "centuryplay-policy"
        val DEFAULT_USAGES = intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)
        private val FORMAT: AudioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
    }
}
