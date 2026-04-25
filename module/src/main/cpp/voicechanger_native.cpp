#include <aaudio/AAudio.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <sys/types.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>
#include <string_view>
#include <utility>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace {

constexpr const char *kTag = "qwulivoiceNative";
constexpr double kPi = 3.14159265358979323846;
constexpr int64_t kConfigCacheWindowMs = 800;
constexpr int64_t kSoundpadCacheWindowMs = 160;

typedef int (*HookFunType)(void *func, void *replace, void **backup);
typedef int (*UnhookFunType)(void *func);
typedef void (*NativeOnModuleLoaded)(const char *name, void *handle);

typedef struct {
    uint32_t version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
} NativeAPIEntries;

using AAudioStreamReadFn = aaudio_result_t (*)(AAudioStream *stream, void *buffer, int32_t numFrames, int64_t timeoutNanoseconds);
using AAudioOpenStreamFn = aaudio_result_t (*)(AAudioStreamBuilder *builder, AAudioStream **stream);
using AAudioSetDataCallbackFn = aaudio_result_t (*)(AAudioStreamBuilder *builder, AAudioStream_dataCallback callback, void *userData);
using AAudioStreamCloseFn = aaudio_result_t (*)(AAudioStream *stream);
using AAudioBuilderDeleteFn = aaudio_result_t (*)(AAudioStreamBuilder *builder);
using NativeAudioRecordReadFn = ssize_t (*)(void *thiz, void *buffer, size_t userSize, bool blocking);
using NativeAudioRecordCtorFn = void (*)(
    void *thiz,
    int32_t audioSource,
    uint32_t sampleRate,
    int32_t audioFormat,
    uint32_t channelMask,
    const void *attributionSourceState,
    size_t frameCount,
    const void *callback,
    uint32_t notificationFrames,
    int32_t sessionId,
    int32_t transferType,
    uint32_t inputFlags,
    const void *attributes,
    int32_t selectedDeviceId,
    int32_t micDirection,
    float micFieldDimension
);
using JniWebRtcAudioRecordedFn = void (*)(JNIEnv *env, jobject thiz, jlong nativeAudioRecord, jint byteCount, jlong captureTimestampNs);
using JniWebRtcVoiceEngineRecordedFn = void (*)(JNIEnv *env, jobject thiz, jint byteCount, jlong nativeAudioRecord);

struct NativeConfigSnapshot {
    bool enabled = false;
    bool allowed = false;
    std::string modeId = "original";
    int effectStrength = 85;
    int micGainPercent = 0;
    int64_t loadedAtMs = 0;
    bool valid = false;
};

struct NativeSoundpadSnapshot {
    bool active = false;
    std::string pcmPath;
    int sampleRate = 48000;
    int mixPercent = 0;
    int gainPercent = 100;
    bool looping = false;
    int64_t sessionId = 0;
    int64_t loadedAtMs = 0;
    bool valid = false;
};

struct StreamState {
    double phase = 0.0;
    float lowPass = 0.0f;
    float previousInput = 0.0f;
    bool wasPositive = false;
    float subPolarity = 1.0f;
    std::vector<int16_t> soundpadSamples;
    std::string soundpadPcmPath;
    int soundpadSourceSampleRate = 48000;
    double soundpadPosition = 0.0;
    int64_t soundpadSessionId = 0;
    int64_t soundpadCompletedSessionId = 0;
    bool soundpadLooping = false;
    bool soundpadActive = false;
    float soundpadMix = 0.0f;
};

struct CallbackBinding {
    AAudioStream_dataCallback callback = nullptr;
    void *userData = nullptr;
};

struct NativeAudioRecordInfo {
    int sampleRate = 48000;
    uint32_t channelMask = 0;
    int format = 1;
    int source = 0;
};

HookFunType gHookFunction = nullptr;
AAudioStreamReadFn gBackupRead = nullptr;
AAudioOpenStreamFn gBackupOpenStream = nullptr;
AAudioSetDataCallbackFn gBackupSetDataCallback = nullptr;
AAudioStreamCloseFn gBackupClose = nullptr;
AAudioBuilderDeleteFn gBackupBuilderDelete = nullptr;
NativeAudioRecordReadFn gBackupNativeAudioRecordRead = nullptr;
NativeAudioRecordCtorFn gBackupNativeAudioRecordCtor1 = nullptr;
NativeAudioRecordCtorFn gBackupNativeAudioRecordCtor2 = nullptr;
JniWebRtcAudioRecordedFn gBackupWebRtcAudioRecorded = nullptr;
JniWebRtcVoiceEngineRecordedFn gBackupWebRtcVoiceRecorded = nullptr;

JavaVM *gJavaVm = nullptr;
jclass gBridgeClass = nullptr;
jclass gSystemClass = nullptr;
jmethodID gSnapshotMethod = nullptr;
jmethodID gReportMethod = nullptr;
jmethodID gIdentityHashCodeMethod = nullptr;
jmethodID gSnapshotGetEnabled = nullptr;
jmethodID gSnapshotGetAllowed = nullptr;
jmethodID gSnapshotGetModeId = nullptr;
jmethodID gSnapshotGetEffectStrength = nullptr;
jmethodID gSnapshotGetMicGainPercent = nullptr;
jmethodID gSoundpadSnapshotMethod = nullptr;
jmethodID gSoundpadGetActive = nullptr;
jmethodID gSoundpadGetPcmPath = nullptr;
jmethodID gSoundpadGetSampleRate = nullptr;
jmethodID gSoundpadGetMixPercent = nullptr;
jmethodID gSoundpadGetGainPercent = nullptr;
jmethodID gSoundpadGetLooping = nullptr;
jmethodID gSoundpadGetSessionId = nullptr;

std::mutex gConfigMutex;
NativeConfigSnapshot gCachedConfig;
std::mutex gSoundpadMutex;
NativeSoundpadSnapshot gCachedSoundpad;
std::mutex gStateMutex;
std::unordered_map<AAudioStream *, StreamState> gStreamStates;
std::unordered_map<AAudioStreamBuilder *, bool> gBuilderUsesCallback;
std::unordered_map<AAudioStreamBuilder *, CallbackBinding> gBuilderCallbacks;
std::unordered_map<AAudioStream *, CallbackBinding> gStreamCallbacks;
std::unordered_map<int, StreamState> gWebRtcStates;
std::unordered_map<void *, StreamState> gNativeRecordStates;
std::unordered_map<void *, NativeAudioRecordInfo> gNativeAudioRecords;
std::unordered_set<std::string> gLoggedLibraryEvents;
std::string gProcessPackageName;
bool gHooksEnabled = false;

bool gReadHookInstalled = false;
bool gOpenHookInstalled = false;
bool gCallbackHookInstalled = false;
bool gCloseHookInstalled = false;
bool gBuilderDeleteHookInstalled = false;
bool gWebRtcAudioHookInstalled = false;
bool gWebRtcVoiceHookInstalled = false;
bool gNativeAudioReadHookInstalled = false;
bool gNativeAudioCtor1HookInstalled = false;
bool gNativeAudioCtor2HookInstalled = false;

std::string currentPackageName();
void processInt16(
    int16_t *samples,
    int32_t numFrames,
    int channelCount,
    int sampleRate,
    const NativeConfigSnapshot *config,
    const NativeSoundpadSnapshot *soundpad,
    StreamState &state
);

int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

void logLine(int priority, const std::string &message) {
    __android_log_write(priority, kTag, message.c_str());
}

bool endsWith(std::string_view value, std::string_view suffix) {
    return value.size() >= suffix.size() &&
           value.substr(value.size() - suffix.size()) == suffix;
}

bool isInterestingLibrary(std::string_view name) {
    return endsWith(name, "libaaudio.so") ||
           endsWith(name, "libaudioclient.so") ||
           endsWith(name, "libdiscord.so") ||
           endsWith(name, "libjingle_peerconnection_so.so") ||
           endsWith(name, "libVoipEngineNative.so") ||
           endsWith(name, "libtmessages.49.so") ||
           endsWith(name, "libtmessages.so");
}

void logLibraryEventOnce(std::string key, std::string message) {
    bool shouldLog = false;
    {
        std::lock_guard<std::mutex> lock(gStateMutex);
        shouldLog = gLoggedLibraryEvents.insert(std::move(key)).second;
    }
    if (shouldLog) {
        logLine(ANDROID_LOG_INFO, message);
    }
}

JNIEnv *getEnv(bool *didAttach) {
    if (didAttach != nullptr) {
        *didAttach = false;
    }
    if (gJavaVm == nullptr) {
        return nullptr;
    }

    JNIEnv *env = nullptr;
    const jint status = gJavaVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_OK) {
        return env;
    }
    if (status != JNI_EDETACHED) {
        return nullptr;
    }
    if (gJavaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        return nullptr;
    }
    if (didAttach != nullptr) {
        *didAttach = true;
    }
    return env;
}

void releaseEnv(bool didAttach) {
    if (didAttach && gJavaVm != nullptr) {
        gJavaVm->DetachCurrentThread();
    }
}

bool resolveBridge(JNIEnv *env) {
    if (env == nullptr || gBridgeClass == nullptr || gSnapshotMethod == nullptr || gReportMethod == nullptr) {
        return false;
    }
    return gSnapshotGetEnabled != nullptr &&
           gSnapshotGetAllowed != nullptr &&
           gSnapshotGetModeId != nullptr &&
           gSnapshotGetEffectStrength != nullptr &&
           gSnapshotGetMicGainPercent != nullptr;
}

bool resolveSoundpadBridge(JNIEnv *env) {
    if (env == nullptr || gBridgeClass == nullptr || gSoundpadSnapshotMethod == nullptr) {
        return false;
    }
    return gSoundpadGetActive != nullptr &&
           gSoundpadGetPcmPath != nullptr &&
           gSoundpadGetSampleRate != nullptr &&
           gSoundpadGetMixPercent != nullptr &&
           gSoundpadGetGainPercent != nullptr &&
           gSoundpadGetLooping != nullptr &&
           gSoundpadGetSessionId != nullptr;
}

void clearPendingException(JNIEnv *env) {
    if (env != nullptr && env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

jfieldID findFieldInHierarchy(JNIEnv *env, jobject target, const char *name, const char *signature) {
    if (env == nullptr || target == nullptr || name == nullptr || signature == nullptr) {
        return nullptr;
    }

    jclass current = env->GetObjectClass(target);
    while (current != nullptr) {
        jfieldID field = env->GetFieldID(current, name, signature);
        if (field != nullptr) {
            env->DeleteLocalRef(current);
            return field;
        }
        clearPendingException(env);
        jclass parent = env->GetSuperclass(current);
        env->DeleteLocalRef(current);
        current = parent;
    }
    return nullptr;
}

jobject findObjectField(JNIEnv *env, jobject target, const std::vector<const char *> &names, const char *signature) {
    if (env == nullptr || target == nullptr) {
        return nullptr;
    }
    for (const char *name : names) {
        const jfieldID field = findFieldInHierarchy(env, target, name, signature);
        if (field == nullptr) {
            continue;
        }
        jobject value = env->GetObjectField(target, field);
        if (!env->ExceptionCheck()) {
            return value;
        }
        clearPendingException(env);
    }
    return nullptr;
}

int findIntField(JNIEnv *env, jobject target, const std::vector<const char *> &names) {
    if (env == nullptr || target == nullptr) {
        return 0;
    }
    for (const char *name : names) {
        const jfieldID field = findFieldInHierarchy(env, target, name, "I");
        if (field == nullptr) {
            continue;
        }
        const jint value = env->GetIntField(target, field);
        if (!env->ExceptionCheck() && value > 0) {
            return value;
        }
        clearPendingException(env);
    }
    return 0;
}

int callIntMethodIfPresent(JNIEnv *env, jobject target, const char *methodName, const char *signature) {
    if (env == nullptr || target == nullptr || methodName == nullptr || signature == nullptr) {
        return 0;
    }
    jclass clazz = env->GetObjectClass(target);
    if (clazz == nullptr) {
        return 0;
    }
    jmethodID method = env->GetMethodID(clazz, methodName, signature);
    env->DeleteLocalRef(clazz);
    if (method == nullptr) {
        clearPendingException(env);
        return 0;
    }
    const jint value = env->CallIntMethod(target, method);
    if (env->ExceptionCheck()) {
        clearPendingException(env);
        return 0;
    }
    return value > 0 ? value : 0;
}

int objectKey(JNIEnv *env, jobject target) {
    if (env == nullptr || target == nullptr || gSystemClass == nullptr || gIdentityHashCodeMethod == nullptr) {
        return 0;
    }
    const jint value = env->CallStaticIntMethod(gSystemClass, gIdentityHashCodeMethod, target);
    if (env->ExceptionCheck()) {
        clearPendingException(env);
        return 0;
    }
    return value;
}

NativeConfigSnapshot loadConfigSnapshot(const std::string &packageName) {
    const auto now = nowMs();
    {
        std::lock_guard<std::mutex> lock(gConfigMutex);
        if (gCachedConfig.valid && now - gCachedConfig.loadedAtMs < kConfigCacheWindowMs) {
            return gCachedConfig;
        }
    }

    bool didAttach = false;
    JNIEnv *env = getEnv(&didAttach);
    if (!resolveBridge(env)) {
        releaseEnv(didAttach);
        std::lock_guard<std::mutex> lock(gConfigMutex);
        return gCachedConfig;
    }

    NativeConfigSnapshot snapshot;
    jstring jPackageName = env->NewStringUTF(packageName.c_str());
    jobject jSnapshot = env->CallStaticObjectMethod(gBridgeClass, gSnapshotMethod, jPackageName);
    env->DeleteLocalRef(jPackageName);

    if (jSnapshot != nullptr) {
        snapshot.enabled = env->CallBooleanMethod(jSnapshot, gSnapshotGetEnabled);
        snapshot.allowed = env->CallBooleanMethod(jSnapshot, gSnapshotGetAllowed);
        snapshot.effectStrength = env->CallIntMethod(jSnapshot, gSnapshotGetEffectStrength);
        snapshot.micGainPercent = env->CallIntMethod(jSnapshot, gSnapshotGetMicGainPercent);
        jstring mode = static_cast<jstring>(env->CallObjectMethod(jSnapshot, gSnapshotGetModeId));
        if (mode != nullptr) {
            const char *modeChars = env->GetStringUTFChars(mode, nullptr);
            snapshot.modeId = modeChars != nullptr ? modeChars : "original";
            if (modeChars != nullptr) {
                env->ReleaseStringUTFChars(mode, modeChars);
            }
            env->DeleteLocalRef(mode);
        }
        env->DeleteLocalRef(jSnapshot);
        snapshot.valid = true;
        snapshot.loadedAtMs = now;
    }

    releaseEnv(didAttach);

    std::lock_guard<std::mutex> lock(gConfigMutex);
    if (snapshot.valid) {
        gCachedConfig = snapshot;
    }
    return gCachedConfig;
}

NativeSoundpadSnapshot loadSoundpadSnapshot() {
    const auto now = nowMs();
    {
        std::lock_guard<std::mutex> lock(gSoundpadMutex);
        if (gCachedSoundpad.valid && now - gCachedSoundpad.loadedAtMs < kSoundpadCacheWindowMs) {
            return gCachedSoundpad;
        }
    }

    bool didAttach = false;
    JNIEnv *env = getEnv(&didAttach);
    if (!resolveSoundpadBridge(env)) {
        releaseEnv(didAttach);
        std::lock_guard<std::mutex> lock(gSoundpadMutex);
        return gCachedSoundpad;
    }

    NativeSoundpadSnapshot snapshot;
    jobject jSnapshot = env->CallStaticObjectMethod(gBridgeClass, gSoundpadSnapshotMethod);
    if (jSnapshot != nullptr) {
        snapshot.active = env->CallBooleanMethod(jSnapshot, gSoundpadGetActive);
        snapshot.sampleRate = env->CallIntMethod(jSnapshot, gSoundpadGetSampleRate);
        snapshot.mixPercent = env->CallIntMethod(jSnapshot, gSoundpadGetMixPercent);
        snapshot.gainPercent = env->CallIntMethod(jSnapshot, gSoundpadGetGainPercent);
        snapshot.looping = env->CallBooleanMethod(jSnapshot, gSoundpadGetLooping);
        snapshot.sessionId = env->CallLongMethod(jSnapshot, gSoundpadGetSessionId);
        jstring jPath = static_cast<jstring>(env->CallObjectMethod(jSnapshot, gSoundpadGetPcmPath));
        if (jPath != nullptr) {
            const char *pathChars = env->GetStringUTFChars(jPath, nullptr);
            snapshot.pcmPath = pathChars != nullptr ? pathChars : "";
            if (pathChars != nullptr) {
                env->ReleaseStringUTFChars(jPath, pathChars);
            }
            env->DeleteLocalRef(jPath);
        }
        env->DeleteLocalRef(jSnapshot);
        snapshot.valid = true;
        snapshot.loadedAtMs = now;
    }

    releaseEnv(didAttach);

    std::lock_guard<std::mutex> lock(gSoundpadMutex);
    if (snapshot.valid) {
        gCachedSoundpad = snapshot;
    }
    return gCachedSoundpad;
}

void reportEvent(
    const std::string &packageName,
    const std::string &source,
    const std::string &detail,
    bool force,
    const std::string &rateKey,
    int64_t minIntervalMs
) {
    if (packageName.empty() || source.empty()) {
        return;
    }

    bool didAttach = false;
    JNIEnv *env = getEnv(&didAttach);
    if (!resolveBridge(env)) {
        releaseEnv(didAttach);
        return;
    }

    jstring jPackage = env->NewStringUTF(packageName.c_str());
    jstring jSource = env->NewStringUTF(source.c_str());
    jstring jDetail = env->NewStringUTF(detail.c_str());
    jstring jRateKey = env->NewStringUTF(rateKey.c_str());
    env->CallStaticVoidMethod(
        gBridgeClass,
        gReportMethod,
        jPackage,
        jSource,
        jDetail,
        static_cast<jboolean>(force),
        jRateKey,
        static_cast<jlong>(minIntervalMs)
    );
    env->DeleteLocalRef(jPackage);
    env->DeleteLocalRef(jSource);
    env->DeleteLocalRef(jDetail);
    env->DeleteLocalRef(jRateKey);
    releaseEnv(didAttach);
}

float softClip(float value) {
    const float limited = value / (1.0f + std::abs(value) * 0.85f);
    if (limited < -1.0f) {
        return -1.0f;
    }
    if (limited > 1.0f) {
        return 1.0f;
    }
    return limited;
}

float applyRobot(float input, int sampleRate, float strength, StreamState &state) {
    const float carrierHz = 55.0f + strength * 125.0f;
    const float carrier = std::sin(state.phase) >= 0.0 ? 1.0f : -1.0f;
    state.phase += (2.0 * kPi * carrierHz / sampleRate);
    if (state.phase >= 2.0 * kPi) {
        state.phase -= 2.0 * kPi;
    }

    const float crushResolution = 14.0f + strength * 50.0f;
    const float crushed = std::round(input * crushResolution) / crushResolution;
    return softClip(input * 0.18f + crushed * carrier * (0.82f + strength * 0.10f));
}

float applyBright(float input, float strength, StreamState &state) {
    const float emphasis = input - (state.previousInput * (0.72f + strength * 0.18f));
    state.previousInput = input;
    const float sharpened = input * (0.65f - strength * 0.10f) + emphasis * (1.15f + strength * 0.95f);
    return softClip(sharpened * (1.02f + strength * 0.25f));
}

float applyDeep(float input, float strength, StreamState &state) {
    float alpha = 0.10f - strength * 0.05f;
    if (alpha < 0.03f) {
        alpha = 0.03f;
    } else if (alpha > 0.12f) {
        alpha = 0.12f;
    }
    state.lowPass += (input - state.lowPass) * alpha;

    const bool positive = input >= 0.0f;
    if (positive && !state.wasPositive) {
        state.subPolarity *= -1.0f;
    }
    state.wasPositive = positive;

    const float sub = state.lowPass * state.subPolarity * (0.18f + strength * 0.35f);
    return softClip(state.lowPass * (1.04f + strength * 0.26f) + sub);
}

float applyMicBoost(float input, int boost) {
    const float amount = std::clamp(static_cast<float>(boost), 0.0f, 101.0f);
    if (amount <= 0.0f) {
        return std::clamp(input, -1.0f, 1.0f);
    }

    if (amount >= 101.0f) {
        const float clipped = std::clamp(input * 76.0f, -1.0f, 1.0f);
        return (clipped >= 0.0f ? 1.0f : -1.0f) * std::pow(std::abs(clipped), 0.55f);
    }

    const float normalized = amount / 100.0f;
    const float gain = 1.0f + (3.0f * std::pow(normalized, 1.65f));
    const float saturationMix = 0.16f * normalized * normalized * normalized;
    const float clipped = std::clamp(input * gain, -1.0f, 1.0f);
    const float saturated = (clipped >= 0.0f ? 1.0f : -1.0f) * std::pow(std::abs(clipped), 0.72f);
    return std::clamp((clipped * (1.0f - saturationMix)) + (saturated * saturationMix), -1.0f, 1.0f);
}

float processSample(float input, int sampleRate, const NativeConfigSnapshot &config, StreamState &state) {
    const float strength = std::clamp(static_cast<float>(config.effectStrength), 0.0f, 100.0f) / 100.0f;
    const int safeSampleRate = sampleRate < 8000 ? 8000 : sampleRate;

    float effected = input;
    if (config.modeId == "robot") {
        effected = applyRobot(input, safeSampleRate, strength, state);
    } else if (config.modeId == "bright") {
        effected = applyBright(input, strength, state);
    } else if (config.modeId == "deep") {
        effected = applyDeep(input, strength, state);
    }

    return applyMicBoost(effected, config.micGainPercent);
}

float computeSoundpadMix(int mixPercent, int gainPercent) {
    const float mix = std::pow(std::clamp(static_cast<float>(mixPercent), 0.0f, 100.0f) / 100.0f, 1.18f);
    const float slotGain = std::pow(std::clamp(static_cast<float>(gainPercent), 0.0f, 160.0f) / 100.0f, 0.92f);
    return std::clamp(mix * slotGain * 0.92f, 0.0f, 1.35f);
}

std::vector<int16_t> loadPcmFile(const std::string &path) {
    if (path.empty()) {
        return {};
    }
    FILE *file = std::fopen(path.c_str(), "rb");
    if (file == nullptr) {
        return {};
    }
    std::fseek(file, 0, SEEK_END);
    const long size = std::ftell(file);
    std::rewind(file);
    if (size < 2) {
        std::fclose(file);
        return {};
    }
    std::vector<int16_t> samples(static_cast<size_t>(size / 2));
    const size_t readCount = std::fread(samples.data(), sizeof(int16_t), samples.size(), file);
    std::fclose(file);
    samples.resize(readCount);
    return samples;
}

bool prepareSoundpadRuntime(StreamState &state, const NativeSoundpadSnapshot *soundpad) {
    if (soundpad == nullptr || !soundpad->active || soundpad->pcmPath.empty() || soundpad->sampleRate <= 0) {
        state.soundpadActive = false;
        state.soundpadMix = 0.0f;
        return false;
    }

    const bool needsReload =
        soundpad->sessionId != state.soundpadSessionId ||
        soundpad->pcmPath != state.soundpadPcmPath;

    if (state.soundpadCompletedSessionId == soundpad->sessionId &&
        state.soundpadPcmPath == soundpad->pcmPath &&
        !soundpad->looping) {
        state.soundpadActive = false;
        state.soundpadMix = 0.0f;
        return false;
    }

    if (needsReload) {
        state.soundpadSamples = loadPcmFile(soundpad->pcmPath);
        state.soundpadPcmPath = soundpad->pcmPath;
        state.soundpadSourceSampleRate = std::max(soundpad->sampleRate, 8000);
        state.soundpadPosition = 0.0;
        state.soundpadSessionId = soundpad->sessionId;
        state.soundpadCompletedSessionId = 0;
        state.soundpadActive = !state.soundpadSamples.empty();
    }

    state.soundpadLooping = soundpad->looping;
    state.soundpadMix = computeSoundpadMix(soundpad->mixPercent, soundpad->gainPercent);
    if (state.soundpadSamples.empty()) {
        state.soundpadActive = false;
    }
    return state.soundpadActive && state.soundpadMix > 0.0f;
}

float nextSoundpadSample(int outputSampleRate, StreamState &state) {
    if (!state.soundpadActive || state.soundpadSamples.empty()) {
        return 0.0f;
    }

    const auto frameCount = static_cast<int32_t>(state.soundpadSamples.size());
    if (frameCount <= 0) {
        state.soundpadActive = false;
        return 0.0f;
    }

    if (!state.soundpadLooping && state.soundpadPosition >= frameCount) {
        state.soundpadActive = false;
        state.soundpadCompletedSessionId = state.soundpadSessionId;
        return 0.0f;
    }
    if (state.soundpadLooping && state.soundpadPosition >= frameCount) {
        state.soundpadPosition = std::fmod(state.soundpadPosition, static_cast<double>(frameCount));
    }

    const int base = std::clamp(static_cast<int>(state.soundpadPosition), 0, frameCount - 1);
    const int nextIndex = (base + 1 < frameCount) ? (base + 1) : (state.soundpadLooping ? 0 : base);
    const float fraction = std::clamp(static_cast<float>(state.soundpadPosition - base), 0.0f, 1.0f);
    const float first = static_cast<float>(state.soundpadSamples[base]) / 32768.0f;
    const float second = static_cast<float>(state.soundpadSamples[nextIndex]) / 32768.0f;
    const float sample = first + ((second - first) * fraction);
    const double step = static_cast<double>(state.soundpadSourceSampleRate) /
        static_cast<double>(std::max(outputSampleRate, 8000));
    state.soundpadPosition += std::max(step, 0.1);
    return std::clamp(sample * state.soundpadMix, -1.0f, 1.0f);
}

int resolveWebRtcSampleRate(JNIEnv *env, jobject holder, jobject audioRecord) {
    const int directValue = findIntField(env, holder, {"sampleRate", "sampleRateHz", "sampleRateInHz", "requestedSampleRate"});
    if (directValue > 0) {
        return directValue;
    }
    const int fromAudioRecord = callIntMethodIfPresent(env, audioRecord, "getSampleRate", "()I");
    if (fromAudioRecord > 0) {
        return fromAudioRecord;
    }
    return 48000;
}

int resolveWebRtcChannelCount(JNIEnv *env, jobject holder, jobject audioRecord) {
    const int directValue = findIntField(env, holder, {"channels", "channelCount", "inputChannels", "requestedChannels"});
    if (directValue > 0) {
        return directValue;
    }
    const int fromAudioRecord = callIntMethodIfPresent(env, audioRecord, "getChannelCount", "()I");
    if (fromAudioRecord > 0) {
        return fromAudioRecord;
    }
    return 1;
}

void processWebRtcRecordedBuffer(JNIEnv *env, jobject holder, jint byteCount, const std::string &source) {
    if (env == nullptr || holder == nullptr || byteCount <= 0) {
        return;
    }

    const std::string packageName = currentPackageName();
    if (packageName.empty()) {
        return;
    }

    const NativeConfigSnapshot config = loadConfigSnapshot(packageName);
    const NativeSoundpadSnapshot soundpad = loadSoundpadSnapshot();
    const bool applyVoice = config.valid && config.enabled && config.allowed;
    const bool applySoundpad = soundpad.valid && soundpad.active;
    if (!applyVoice && !applySoundpad) {
        return;
    }

    jobject byteBuffer = findObjectField(env, holder, {"byteBuffer"}, "Ljava/nio/ByteBuffer;");
    if (byteBuffer == nullptr) {
        reportEvent(
            packageName,
            source,
            "Holder has no direct byteBuffer field.",
            false,
            packageName + "|" + source + "|missing-buffer",
            20000
        );
        return;
    }

    void *bufferAddress = env->GetDirectBufferAddress(byteBuffer);
    const jlong bufferCapacity = env->GetDirectBufferCapacity(byteBuffer);
    if (bufferAddress == nullptr || bufferCapacity <= 0) {
        env->DeleteLocalRef(byteBuffer);
        reportEvent(
            packageName,
            source,
            "Direct ByteBuffer address unavailable.",
            false,
            packageName + "|" + source + "|buffer-address",
            20000
        );
        return;
    }

    jobject audioRecord = findObjectField(env, holder, {"audioRecord"}, "Landroid/media/AudioRecord;");
    const int sampleRate = resolveWebRtcSampleRate(env, holder, audioRecord);
    const int channelCount = std::max(resolveWebRtcChannelCount(env, holder, audioRecord), 1);
    const int safeByteCount = std::min(static_cast<int>(bufferCapacity), static_cast<int>(byteCount));
    const int frameCount = safeByteCount / (static_cast<int>(sizeof(int16_t)) * channelCount);

    if (frameCount > 0) {
        const int key = objectKey(env, holder);
        std::lock_guard<std::mutex> lock(gStateMutex);
        StreamState &state = gWebRtcStates[key != 0 ? key : safeByteCount];
        processInt16(
            static_cast<int16_t *>(bufferAddress),
            frameCount,
            channelCount,
            sampleRate,
            applyVoice ? &config : nullptr,
            applySoundpad ? &soundpad : nullptr,
            state
        );
    }

    if (audioRecord != nullptr) {
        env->DeleteLocalRef(audioRecord);
    }
    env->DeleteLocalRef(byteBuffer);

    if (frameCount <= 0) {
        return;
    }

    reportEvent(
        packageName,
        source,
        "Applied JNI WebRTC hook bytes=" + std::to_string(safeByteCount) +
            " rate=" + std::to_string(sampleRate) + "Hz channels=" + std::to_string(channelCount) +
            " mode=" + (applyVoice ? config.modeId : "original") +
            " gain=" + std::to_string(applyVoice ? config.micGainPercent : 0) +
            "% soundpad=" + (applySoundpad ? soundpad.pcmPath : "off"),
        false,
        packageName + "|" + source + "|apply",
        12000
    );
}

void processInt16(
    int16_t *samples,
    int32_t numFrames,
    int channelCount,
    int sampleRate,
    const NativeConfigSnapshot *config,
    const NativeSoundpadSnapshot *soundpad,
    StreamState &state
) {
    const bool applyVoice = config != nullptr && config->valid && config->enabled && config->allowed;
    const bool applySoundpad = prepareSoundpadRuntime(state, soundpad);
    if (!applyVoice && !applySoundpad) {
        return;
    }

    const int safeChannels = std::max(channelCount, 1);
    for (int32_t frame = 0; frame < numFrames; ++frame) {
        const float pad = applySoundpad ? nextSoundpadSample(sampleRate, state) : 0.0f;
        for (int channel = 0; channel < safeChannels; ++channel) {
            const int32_t index = frame * safeChannels + channel;
            float output = static_cast<float>(samples[index]) / 32768.0f;
            if (applyVoice) {
                output = processSample(output, sampleRate, *config, state);
            }
            if (applySoundpad) {
                output = softClip(output + pad);
            }
            const auto packed = static_cast<int32_t>(std::lrint(output * static_cast<float>(INT16_MAX)));
            samples[index] = static_cast<int16_t>(std::clamp(packed, static_cast<int32_t>(INT16_MIN), static_cast<int32_t>(INT16_MAX)));
        }
    }
}

void processFloat(
    float *samples,
    int32_t numFrames,
    int channelCount,
    int sampleRate,
    const NativeConfigSnapshot *config,
    const NativeSoundpadSnapshot *soundpad,
    StreamState &state
) {
    const bool applyVoice = config != nullptr && config->valid && config->enabled && config->allowed;
    const bool applySoundpad = prepareSoundpadRuntime(state, soundpad);
    if (!applyVoice && !applySoundpad) {
        return;
    }

    const int safeChannels = std::max(channelCount, 1);
    for (int32_t frame = 0; frame < numFrames; ++frame) {
        const float pad = applySoundpad ? nextSoundpadSample(sampleRate, state) : 0.0f;
        for (int channel = 0; channel < safeChannels; ++channel) {
            const int32_t index = frame * safeChannels + channel;
            float output = samples[index];
            if (applyVoice) {
                output = processSample(output, sampleRate, *config, state);
            }
            if (applySoundpad) {
                output = softClip(output + pad);
            }
            samples[index] = output;
        }
    }
}

std::string currentPackageName() {
    std::lock_guard<std::mutex> lock(gStateMutex);
    return gProcessPackageName;
}

std::string formatLabel(aaudio_format_t format) {
    switch (format) {
        case AAUDIO_FORMAT_PCM_I16:
            return "PCM_I16";
        case AAUDIO_FORMAT_PCM_FLOAT:
            return "PCM_FLOAT";
        default:
            return std::to_string(format);
    }
}

int nativeChannelCount(uint32_t channelMask) {
    const int count = __builtin_popcount(channelMask);
    return count > 0 ? count : 1;
}

bool isNativePcm16(int format) {
    return format == 1;
}

bool isNativePcmFloat(int format) {
    return format == 5;
}

void registerNativeAudioRecord(void *record, int32_t audioSource, uint32_t sampleRate, int32_t audioFormat, uint32_t channelMask) {
    if (record == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(gStateMutex);
    NativeAudioRecordInfo info;
    info.source = audioSource;
    info.sampleRate = sampleRate > 0 ? static_cast<int>(sampleRate) : 48000;
    info.format = audioFormat;
    info.channelMask = channelMask;
    gNativeAudioRecords[record] = info;
    gNativeRecordStates.try_emplace(record, StreamState{});
}

bool hooksEnabled() {
    std::lock_guard<std::mutex> lock(gStateMutex);
    return gHooksEnabled && !gProcessPackageName.empty();
}

void installHookIfPresent(void *handle, const char *symbolName, void *replacement, void **backupStorage, bool *installedFlag) {
    if (!hooksEnabled() || gHookFunction == nullptr || handle == nullptr || installedFlag == nullptr || *installedFlag) {
        return;
    }
    void *symbol = dlsym(handle, symbolName);
    if (symbol == nullptr) {
        return;
    }
    if (gHookFunction(symbol, replacement, backupStorage) == 0) {
        *installedFlag = true;
        logLine(ANDROID_LOG_INFO, std::string("Installed hook for ") + symbolName);
    } else {
        logLine(ANDROID_LOG_WARN, std::string("Failed to install hook for ") + symbolName);
    }
}

void hookedWebRtcAudioNativeDataIsRecorded(JNIEnv *env, jobject thiz, jlong nativeAudioRecord, jint byteCount, jlong captureTimestampNs) {
    processWebRtcRecordedBuffer(env, thiz, byteCount, "JNI.WebRtcAudioRecord");
    if (gBackupWebRtcAudioRecorded != nullptr) {
        gBackupWebRtcAudioRecorded(env, thiz, nativeAudioRecord, byteCount, captureTimestampNs);
    }
}

void hookedWebRtcVoiceNativeDataIsRecorded(JNIEnv *env, jobject thiz, jint byteCount, jlong nativeAudioRecord) {
    processWebRtcRecordedBuffer(env, thiz, byteCount, "JNI.WebRtcVoiceRecord");
    if (gBackupWebRtcVoiceRecorded != nullptr) {
        gBackupWebRtcVoiceRecorded(env, thiz, byteCount, nativeAudioRecord);
    }
}

void installJniHooks(void *handle) {
    if (!hooksEnabled()) {
        return;
    }
    installHookIfPresent(
        handle,
        "Java_org_webrtc_audio_WebRtcAudioRecord_nativeDataIsRecorded",
        reinterpret_cast<void *>(hookedWebRtcAudioNativeDataIsRecorded),
        reinterpret_cast<void **>(&gBackupWebRtcAudioRecorded),
        &gWebRtcAudioHookInstalled
    );
    installHookIfPresent(
        handle,
        "Java_org_webrtc_voiceengine_WebRtcAudioRecord_nativeDataIsRecorded",
        reinterpret_cast<void *>(hookedWebRtcVoiceNativeDataIsRecorded),
        reinterpret_cast<void **>(&gBackupWebRtcVoiceRecorded),
        &gWebRtcVoiceHookInstalled
    );
}

void hookedNativeAudioRecordCtor1(
    void *thiz,
    int32_t audioSource,
    uint32_t sampleRate,
    int32_t audioFormat,
    uint32_t channelMask,
    const void *attributionSourceState,
    size_t frameCount,
    const void *callback,
    uint32_t notificationFrames,
    int32_t sessionId,
    int32_t transferType,
    uint32_t inputFlags,
    const void *attributes,
    int32_t selectedDeviceId,
    int32_t micDirection,
    float micFieldDimension
) {
    if (gBackupNativeAudioRecordCtor1 != nullptr) {
        gBackupNativeAudioRecordCtor1(
            thiz,
            audioSource,
            sampleRate,
            audioFormat,
            channelMask,
            attributionSourceState,
            frameCount,
            callback,
            notificationFrames,
            sessionId,
            transferType,
            inputFlags,
            attributes,
            selectedDeviceId,
            micDirection,
            micFieldDimension
        );
    }
    registerNativeAudioRecord(thiz, audioSource, sampleRate, audioFormat, channelMask);
}

void hookedNativeAudioRecordCtor2(
    void *thiz,
    int32_t audioSource,
    uint32_t sampleRate,
    int32_t audioFormat,
    uint32_t channelMask,
    const void *attributionSourceState,
    size_t frameCount,
    const void *callback,
    uint32_t notificationFrames,
    int32_t sessionId,
    int32_t transferType,
    uint32_t inputFlags,
    const void *attributes,
    int32_t selectedDeviceId,
    int32_t micDirection,
    float micFieldDimension
) {
    if (gBackupNativeAudioRecordCtor2 != nullptr) {
        gBackupNativeAudioRecordCtor2(
            thiz,
            audioSource,
            sampleRate,
            audioFormat,
            channelMask,
            attributionSourceState,
            frameCount,
            callback,
            notificationFrames,
            sessionId,
            transferType,
            inputFlags,
            attributes,
            selectedDeviceId,
            micDirection,
            micFieldDimension
        );
    }
    registerNativeAudioRecord(thiz, audioSource, sampleRate, audioFormat, channelMask);
}

ssize_t hookedNativeAudioRecordRead(void *thiz, void *buffer, size_t userSize, bool blocking) {
    if (gBackupNativeAudioRecordRead == nullptr) {
        return -1;
    }

    const ssize_t result = gBackupNativeAudioRecordRead(thiz, buffer, userSize, blocking);
    if (result <= 0 || thiz == nullptr || buffer == nullptr) {
        return result;
    }

    const std::string packageName = currentPackageName();
    if (packageName.empty()) {
        return result;
    }

    const NativeConfigSnapshot config = loadConfigSnapshot(packageName);
    const NativeSoundpadSnapshot soundpad = loadSoundpadSnapshot();
    const bool applyVoice = config.valid && config.enabled && config.allowed;
    const bool applySoundpad = soundpad.valid && soundpad.active;
    if (!applyVoice && !applySoundpad) {
        return result;
    }

    NativeAudioRecordInfo info;
    {
        std::lock_guard<std::mutex> lock(gStateMutex);
        const auto iterator = gNativeAudioRecords.find(thiz);
        if (iterator != gNativeAudioRecords.end()) {
            info = iterator->second;
        }
    }

    const int sampleRate = info.sampleRate > 0 ? info.sampleRate : 48000;
    const int channelCount = nativeChannelCount(info.channelMask);

    bool applied = false;
    {
        std::lock_guard<std::mutex> lock(gStateMutex);
        StreamState &state = gNativeRecordStates[thiz];
        if (isNativePcm16(info.format)) {
            const int frameCount = static_cast<int>(result) / static_cast<int>(sizeof(int16_t) * channelCount);
            if (frameCount > 0) {
                processInt16(
                    static_cast<int16_t *>(buffer),
                    frameCount,
                    channelCount,
                    sampleRate,
                    applyVoice ? &config : nullptr,
                    applySoundpad ? &soundpad : nullptr,
                    state
                );
                applied = true;
            }
        } else if (isNativePcmFloat(info.format)) {
            const int frameCount = static_cast<int>(result) / static_cast<int>(sizeof(float) * channelCount);
            if (frameCount > 0) {
                processFloat(
                    static_cast<float *>(buffer),
                    frameCount,
                    channelCount,
                    sampleRate,
                    applyVoice ? &config : nullptr,
                    applySoundpad ? &soundpad : nullptr,
                    state
                );
                applied = true;
            }
        }
    }

    if (applied) {
        reportEvent(
            packageName,
            "Native.AudioRecord.read",
            "Applied libaudioclient hook bytes=" + std::to_string(result) +
                " rate=" + std::to_string(sampleRate) + "Hz channels=" + std::to_string(channelCount) +
                " format=" + std::to_string(info.format) +
                " source=" + std::to_string(info.source) +
                " mode=" + (applyVoice ? config.modeId : "original") +
                " gain=" + std::to_string(applyVoice ? config.micGainPercent : 0) +
                "% soundpad=" + (applySoundpad ? soundpad.pcmPath : "off"),
            false,
            packageName + "|Native.AudioRecord.read|apply",
            12000
        );
    }

    return result;
}

void installAudioClientHooks(void *handle) {
    if (!hooksEnabled()) {
        return;
    }
    installHookIfPresent(
        handle,
        "_ZN7android11AudioRecord4readEPvmb",
        reinterpret_cast<void *>(hookedNativeAudioRecordRead),
        reinterpret_cast<void **>(&gBackupNativeAudioRecordRead),
        &gNativeAudioReadHookInstalled
    );
    installHookIfPresent(
        handle,
        "_ZN7android11AudioRecordC1E14audio_source_tj14audio_format_t20audio_channel_mask_tRKNS_7content22AttributionSourceStateEmRKNS_2wpINS0_20IAudioRecordCallbackEEEj15audio_session_tNS0_13transfer_typeE19audio_input_flags_tPK18audio_attributes_ti28audio_microphone_direction_tf",
        reinterpret_cast<void *>(hookedNativeAudioRecordCtor1),
        reinterpret_cast<void **>(&gBackupNativeAudioRecordCtor1),
        &gNativeAudioCtor1HookInstalled
    );
    installHookIfPresent(
        handle,
        "_ZN7android11AudioRecordC2E14audio_source_tj14audio_format_t20audio_channel_mask_tRKNS_7content22AttributionSourceStateEmRKNS_2wpINS0_20IAudioRecordCallbackEEEj15audio_session_tNS0_13transfer_typeE19audio_input_flags_tPK18audio_attributes_ti28audio_microphone_direction_tf",
        reinterpret_cast<void *>(hookedNativeAudioRecordCtor2),
        reinterpret_cast<void **>(&gBackupNativeAudioRecordCtor2),
        &gNativeAudioCtor2HookInstalled
    );
}

aaudio_data_callback_result_t hookedAAudioDataCallback(AAudioStream *stream, void *userData, void *audioData, int32_t numFrames) {
    CallbackBinding binding;
    bool hasBinding = false;
    {
        std::lock_guard<std::mutex> lock(gStateMutex);
        const auto iterator = gStreamCallbacks.find(stream);
        if (iterator != gStreamCallbacks.end()) {
            binding = iterator->second;
            hasBinding = binding.callback != nullptr;
        }
    }

    const std::string packageName = currentPackageName();
    if (stream != nullptr && audioData != nullptr && numFrames > 0 && !packageName.empty()) {
        if (AAudioStream_getDirection(stream) == AAUDIO_DIRECTION_INPUT) {
            const NativeConfigSnapshot config = loadConfigSnapshot(packageName);
            const NativeSoundpadSnapshot soundpad = loadSoundpadSnapshot();
            const bool applyVoice = config.valid && config.enabled && config.allowed;
            const bool applySoundpad = soundpad.valid && soundpad.active;
            if (applyVoice || applySoundpad) {
                const int sampleRate = std::max(AAudioStream_getSampleRate(stream), 8000);
                const int channelCount = std::max(AAudioStream_getChannelCount(stream), 1);
                const auto format = AAudioStream_getFormat(stream);

                bool applied = false;
                {
                    std::lock_guard<std::mutex> lock(gStateMutex);
                    StreamState &state = gStreamStates[stream];
                    switch (format) {
                        case AAUDIO_FORMAT_PCM_I16:
                            processInt16(
                                static_cast<int16_t *>(audioData),
                                numFrames,
                                channelCount,
                                sampleRate,
                                applyVoice ? &config : nullptr,
                                applySoundpad ? &soundpad : nullptr,
                                state
                            );
                            applied = true;
                            break;
                        case AAUDIO_FORMAT_PCM_FLOAT:
                            processFloat(
                                static_cast<float *>(audioData),
                                numFrames,
                                channelCount,
                                sampleRate,
                                applyVoice ? &config : nullptr,
                                applySoundpad ? &soundpad : nullptr,
                                state
                            );
                            applied = true;
                            break;
                        default:
                            reportEvent(
                                packageName,
                                "AAudio.callback",
                                "Unsupported native callback format=" + formatLabel(format),
                                false,
                                packageName + "|AAudio.callback|unsupported",
                                20000
                            );
                            break;
                    }
                }

                if (applied) {
                    reportEvent(
                        packageName,
                        "AAudio.callback",
                        "Applied native callback hook frames=" + std::to_string(numFrames) +
                            " rate=" + std::to_string(sampleRate) + "Hz channels=" + std::to_string(channelCount) +
                            " format=" + formatLabel(format) + " mode=" + (applyVoice ? config.modeId : "original") +
                            " gain=" + std::to_string(applyVoice ? config.micGainPercent : 0) +
                            "% soundpad=" + (applySoundpad ? soundpad.pcmPath : "off"),
                        false,
                        packageName + "|AAudio.callback|apply",
                        12000
                    );
                }
            }
        }
    }

    if (hasBinding && binding.callback != nullptr) {
        return binding.callback(stream, binding.userData != nullptr ? binding.userData : userData, audioData, numFrames);
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

aaudio_result_t hookedAAudioStreamRead(AAudioStream *stream, void *buffer, int32_t numFrames, int64_t timeoutNanoseconds) {
    if (gBackupRead == nullptr) {
        return AAUDIO_ERROR_INTERNAL;
    }

    const aaudio_result_t result = gBackupRead(stream, buffer, numFrames, timeoutNanoseconds);
    if (result <= 0 || stream == nullptr || buffer == nullptr) {
        return result;
    }
    if (AAudioStream_getDirection(stream) != AAUDIO_DIRECTION_INPUT) {
        return result;
    }

    const std::string packageName = currentPackageName();
    if (packageName.empty()) {
        return result;
    }

    const NativeConfigSnapshot config = loadConfigSnapshot(packageName);
    const NativeSoundpadSnapshot soundpad = loadSoundpadSnapshot();
    const bool applyVoice = config.valid && config.enabled && config.allowed;
    const bool applySoundpad = soundpad.valid && soundpad.active;
    if (!applyVoice && !applySoundpad) {
        return result;
    }

    const int sampleRate = std::max(AAudioStream_getSampleRate(stream), 8000);
    const int channelCount = std::max(AAudioStream_getChannelCount(stream), 1);
    const auto format = AAudioStream_getFormat(stream);
    const int32_t sampleCount = result * channelCount;

    if (sampleCount <= 0) {
        return result;
    }

    {
        std::lock_guard<std::mutex> lock(gStateMutex);
        StreamState &state = gStreamStates[stream];

        switch (format) {
            case AAUDIO_FORMAT_PCM_I16:
                processInt16(
                    static_cast<int16_t *>(buffer),
                    result,
                    channelCount,
                    sampleRate,
                    applyVoice ? &config : nullptr,
                    applySoundpad ? &soundpad : nullptr,
                    state
                );
                break;
            case AAUDIO_FORMAT_PCM_FLOAT:
                processFloat(
                    static_cast<float *>(buffer),
                    result,
                    channelCount,
                    sampleRate,
                    applyVoice ? &config : nullptr,
                    applySoundpad ? &soundpad : nullptr,
                    state
                );
                break;
            default:
                reportEvent(
                    packageName,
                    "AAudio.read",
                    "Unsupported native format=" + formatLabel(format),
                    false,
                    packageName + "|AAudio.read|unsupported",
                    20000
                );
                return result;
        }
    }

    reportEvent(
        packageName,
        "AAudio.read",
        "Applied native read hook frames=" + std::to_string(result) +
            " rate=" + std::to_string(sampleRate) + "Hz channels=" + std::to_string(channelCount) +
            " format=" + formatLabel(format) + " mode=" + (applyVoice ? config.modeId : "original") +
            " gain=" + std::to_string(applyVoice ? config.micGainPercent : 0) +
            "% soundpad=" + (applySoundpad ? soundpad.pcmPath : "off"),
        false,
        packageName + "|AAudio.read|apply",
        12000
    );
    return result;
}

aaudio_result_t hookedAAudioStreamClose(AAudioStream *stream) {
    if (stream != nullptr) {
        std::lock_guard<std::mutex> lock(gStateMutex);
        gStreamStates.erase(stream);
        gStreamCallbacks.erase(stream);
    }
    if (gBackupClose == nullptr) {
        return AAUDIO_OK;
    }
    return gBackupClose(stream);
}

aaudio_result_t hookedAAudioBuilderSetDataCallback(AAudioStreamBuilder *builder, AAudioStream_dataCallback callback, void *userData) {
    if (gBackupSetDataCallback == nullptr) {
        return AAUDIO_ERROR_INTERNAL;
    }
    const aaudio_result_t result = gBackupSetDataCallback(
        builder,
        callback != nullptr ? hookedAAudioDataCallback : nullptr,
        userData
    );
    if (result == AAUDIO_OK && builder != nullptr) {
        std::lock_guard<std::mutex> lock(gStateMutex);
        if (callback != nullptr) {
            gBuilderUsesCallback[builder] = true;
            CallbackBinding binding;
            binding.callback = callback;
            binding.userData = userData;
            gBuilderCallbacks[builder] = binding;
        } else {
            gBuilderUsesCallback.erase(builder);
            gBuilderCallbacks.erase(builder);
        }
    }
    return result;
}

aaudio_result_t hookedAAudioBuilderDelete(AAudioStreamBuilder *builder) {
    if (builder != nullptr) {
        std::lock_guard<std::mutex> lock(gStateMutex);
        gBuilderUsesCallback.erase(builder);
        gBuilderCallbacks.erase(builder);
    }
    if (gBackupBuilderDelete == nullptr) {
        return AAUDIO_OK;
    }
    return gBackupBuilderDelete(builder);
}

aaudio_result_t hookedAAudioOpenStream(AAudioStreamBuilder *builder, AAudioStream **stream) {
    if (gBackupOpenStream == nullptr) {
        return AAUDIO_ERROR_INTERNAL;
    }

    const aaudio_result_t result = gBackupOpenStream(builder, stream);
    if (result != AAUDIO_OK || stream == nullptr || *stream == nullptr) {
        return result;
    }
    if (AAudioStream_getDirection(*stream) != AAUDIO_DIRECTION_INPUT) {
        return result;
    }

    const std::string packageName = currentPackageName();
    if (packageName.empty()) {
        return result;
    }

    const int sampleRate = std::max(AAudioStream_getSampleRate(*stream), 8000);
    const int channelCount = std::max(AAudioStream_getChannelCount(*stream), 1);
    const auto format = AAudioStream_getFormat(*stream);

    bool usesCallback = false;
    {
        std::lock_guard<std::mutex> lock(gStateMutex);
        usesCallback = builder != nullptr && gBuilderUsesCallback.find(builder) != gBuilderUsesCallback.end();
        gStreamStates.try_emplace(*stream, StreamState{});
        if (usesCallback && builder != nullptr) {
            const auto iterator = gBuilderCallbacks.find(builder);
            if (iterator != gBuilderCallbacks.end()) {
                gStreamCallbacks[*stream] = iterator->second;
            }
        }
    }

    reportEvent(
        packageName,
        usesCallback ? "AAudio.callback" : "AAudio.open",
        std::string("Opened input stream rate=") + std::to_string(sampleRate) +
            "Hz channels=" + std::to_string(channelCount) +
            " format=" + formatLabel(format) +
            (usesCallback ? " transport=callback" : " transport=read"),
        usesCallback,
        packageName + (usesCallback ? "|AAudio.callback|open" : "|AAudio.open|open"),
        usesCallback ? 0 : 15000
    );
    return result;
}

void installAAudioHooks(void *handle) {
    if (!hooksEnabled()) {
        return;
    }
    installHookIfPresent(handle, "AAudioStream_read", reinterpret_cast<void *>(hookedAAudioStreamRead), reinterpret_cast<void **>(&gBackupRead), &gReadHookInstalled);
    installHookIfPresent(handle, "AAudioStream_close", reinterpret_cast<void *>(hookedAAudioStreamClose), reinterpret_cast<void **>(&gBackupClose), &gCloseHookInstalled);
    installHookIfPresent(handle, "AAudioStreamBuilder_setDataCallback", reinterpret_cast<void *>(hookedAAudioBuilderSetDataCallback), reinterpret_cast<void **>(&gBackupSetDataCallback), &gCallbackHookInstalled);
    installHookIfPresent(handle, "AAudioStreamBuilder_delete", reinterpret_cast<void *>(hookedAAudioBuilderDelete), reinterpret_cast<void **>(&gBackupBuilderDelete), &gBuilderDeleteHookInstalled);
    installHookIfPresent(handle, "AAudioStreamBuilder_openStream", reinterpret_cast<void *>(hookedAAudioOpenStream), reinterpret_cast<void **>(&gBackupOpenStream), &gOpenHookInstalled);
}

void installHooksForLibraryHandle(
    const char *libraryName,
    void *handle,
    bool installJni,
    bool installAAudio,
    bool installAudioClient
) {
    if (handle == nullptr) {
        return;
    }
    if (libraryName != nullptr && isInterestingLibrary(libraryName)) {
        logLibraryEventOnce(
            std::string("seen|") + libraryName,
            std::string("Observed native library load: ") + libraryName
        );
    }
    if (installJni) {
        installJniHooks(handle);
    }
    if (installAAudio) {
        installAAudioHooks(handle);
    }
    if (installAudioClient) {
        installAudioClientHooks(handle);
    }
}

void tryInstallHooksForLibrary(
    const char *libraryName,
    bool allowExplicitLoad,
    bool installJni,
    bool installAAudio,
    bool installAudioClient
) {
    if (!hooksEnabled() || libraryName == nullptr) {
        return;
    }

    void *handle = nullptr;
#ifdef RTLD_NOLOAD
    handle = dlopen(libraryName, RTLD_NOW | RTLD_NOLOAD);
#endif
    if (handle == nullptr && allowExplicitLoad) {
        handle = dlopen(libraryName, RTLD_NOW);
        if (handle != nullptr) {
            logLibraryEventOnce(
                std::string("explicit|") + libraryName,
                std::string("Explicitly loaded native library for hook install: ") + libraryName
            );
        }
    }
    if (handle == nullptr) {
        return;
    }

    installHooksForLibraryHandle(
        libraryName,
        handle,
        installJni,
        installAAudio,
        installAudioClient
    );
}

void onLibraryLoaded(const char *name, void *handle) {
    if (!hooksEnabled() || handle == nullptr) {
        return;
    }
    const bool isAAudioLibrary = name != nullptr && endsWith(name, "libaaudio.so");
    const bool isAudioClientLibrary = name != nullptr && endsWith(name, "libaudioclient.so");
    installHooksForLibraryHandle(
        name,
        handle,
        true,
        isAAudioLibrary,
        isAudioClientLibrary
    );

    if (name != nullptr && endsWith(name, "libVoipEngineNative.so")) {
        tryInstallHooksForLibrary("libjingle_peerconnection_so.so", false, true, false, false);
        tryInstallHooksForLibrary("libaudioclient.so", false, false, false, true);
    }
    if (name != nullptr && endsWith(name, "libdiscord.so")) {
        tryInstallHooksForLibrary("libaudioclient.so", false, false, false, true);
    }
    if (name != nullptr && (endsWith(name, "libtmessages.49.so") || endsWith(name, "libtmessages.so"))) {
        tryInstallHooksForLibrary("libaudioclient.so", false, false, false, true);
    }
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_qwulise_voicechanger_module_NativeAudioBridge_nativeSetProcessPackageName(
    JNIEnv *env,
    jclass,
    jstring packageName
) {
    const char *raw = packageName != nullptr ? env->GetStringUTFChars(packageName, nullptr) : nullptr;
    const std::string attachedPackage = raw != nullptr ? raw : "";
    {
        std::lock_guard<std::mutex> lock(gStateMutex);
        gProcessPackageName = attachedPackage;
        gHooksEnabled = !gProcessPackageName.empty();
    }
    if (raw != nullptr) {
        env->ReleaseStringUTFChars(packageName, raw);
    }
    {
        std::lock_guard<std::mutex> lock(gConfigMutex);
        gCachedConfig.loadedAtMs = 0;
        gCachedConfig.valid = false;
    }
    {
        std::lock_guard<std::mutex> lock(gSoundpadMutex);
        gCachedSoundpad.loadedAtMs = 0;
        gCachedSoundpad.valid = false;
    }
    {
        std::lock_guard<std::mutex> lock(gStateMutex);
        gWebRtcStates.clear();
        gNativeRecordStates.clear();
        gNativeAudioRecords.clear();
        gLoggedLibraryEvents.clear();
    }

    logLine(ANDROID_LOG_INFO, std::string("Attached native bridge to ") + attachedPackage);

    if (hooksEnabled()) {
        tryInstallHooksForLibrary("libaaudio.so", false, false, true, false);
        tryInstallHooksForLibrary("libaudioclient.so", false, false, false, true);
        tryInstallHooksForLibrary("libdiscord.so", false, true, false, false);
        tryInstallHooksForLibrary("libjingle_peerconnection_so.so", false, true, false, false);
        tryInstallHooksForLibrary("libVoipEngineNative.so", false, false, false, false);
        tryInstallHooksForLibrary("libtmessages.49.so", false, true, false, false);
        tryInstallHooksForLibrary("libtmessages.so", false, true, false, false);
    }
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    gJavaVm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }

    jclass localBridgeClass = env->FindClass("com/qwulise/voicechanger/module/NativeAudioBridge");
    if (localBridgeClass == nullptr) {
        logLine(ANDROID_LOG_ERROR, "Failed to resolve NativeAudioBridge");
        return JNI_VERSION_1_6;
    }
    gBridgeClass = reinterpret_cast<jclass>(env->NewGlobalRef(localBridgeClass));
    env->DeleteLocalRef(localBridgeClass);

    jclass localSystemClass = env->FindClass("java/lang/System");
    if (localSystemClass != nullptr) {
        gSystemClass = reinterpret_cast<jclass>(env->NewGlobalRef(localSystemClass));
        env->DeleteLocalRef(localSystemClass);
        gIdentityHashCodeMethod = env->GetStaticMethodID(gSystemClass, "identityHashCode", "(Ljava/lang/Object;)I");
    }

    gSnapshotMethod = env->GetStaticMethodID(
        gBridgeClass,
        "snapshotForNative",
        "(Ljava/lang/String;)Lcom/qwulise/voicechanger/module/NativeConfigSnapshot;"
    );
    gSoundpadSnapshotMethod = env->GetStaticMethodID(
        gBridgeClass,
        "soundpadSnapshotForNative",
        "()Lcom/qwulise/voicechanger/module/NativeSoundpadSnapshot;"
    );
    gReportMethod = env->GetStaticMethodID(
        gBridgeClass,
        "reportNativeEvent",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;J)V"
    );

    jclass snapshotClass = env->FindClass("com/qwulise/voicechanger/module/NativeConfigSnapshot");
    if (snapshotClass != nullptr) {
        gSnapshotGetEnabled = env->GetMethodID(snapshotClass, "getEnabled", "()Z");
        gSnapshotGetAllowed = env->GetMethodID(snapshotClass, "getAllowed", "()Z");
        gSnapshotGetModeId = env->GetMethodID(snapshotClass, "getModeId", "()Ljava/lang/String;");
        gSnapshotGetEffectStrength = env->GetMethodID(snapshotClass, "getEffectStrength", "()I");
        gSnapshotGetMicGainPercent = env->GetMethodID(snapshotClass, "getMicGainPercent", "()I");
        env->DeleteLocalRef(snapshotClass);
    }
    jclass soundpadSnapshotClass = env->FindClass("com/qwulise/voicechanger/module/NativeSoundpadSnapshot");
    if (soundpadSnapshotClass != nullptr) {
        gSoundpadGetActive = env->GetMethodID(soundpadSnapshotClass, "getActive", "()Z");
        gSoundpadGetPcmPath = env->GetMethodID(soundpadSnapshotClass, "getPcmPath", "()Ljava/lang/String;");
        gSoundpadGetSampleRate = env->GetMethodID(soundpadSnapshotClass, "getSampleRate", "()I");
        gSoundpadGetMixPercent = env->GetMethodID(soundpadSnapshotClass, "getMixPercent", "()I");
        gSoundpadGetGainPercent = env->GetMethodID(soundpadSnapshotClass, "getGainPercent", "()I");
        gSoundpadGetLooping = env->GetMethodID(soundpadSnapshotClass, "getLooping", "()Z");
        gSoundpadGetSessionId = env->GetMethodID(soundpadSnapshotClass, "getSessionId", "()J");
        env->DeleteLocalRef(soundpadSnapshotClass);
    }

    logLine(ANDROID_LOG_INFO, "JNI bridge initialized");
    return JNI_VERSION_1_6;
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    if (entries == nullptr) {
        return nullptr;
    }

    gHookFunction = entries->hook_func;
    return onLibraryLoaded;
}
