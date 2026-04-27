#include <android/log.h>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <mutex>
#include <new>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace {

constexpr const char *kTag = "qwvRootAudio";
constexpr double kPi = 3.14159265358979323846;
constexpr uint32_t kAudioEffectLibraryTag = 0x41454c54;  // AELT
constexpr uint32_t kEffectApiVersion = (2u << 16);
constexpr uint32_t kEffectLibraryApiVersion = (3u << 16);
constexpr uint32_t kEffectFlagTypeShift = 0;
constexpr uint32_t kEffectFlagTypePreProc = (3u << kEffectFlagTypeShift);
constexpr uint32_t kEffectFlagDeviceInd = (1u << 16);
constexpr uint32_t kEffectFlagAudioModeInd = (1u << 18);
constexpr uint32_t kEffectFlagAudioSourceInd = (1u << 20);
constexpr uint8_t kAudioFormatPcm16 = 1;
constexpr uint8_t kAudioFormatPcmFloat = 5;
constexpr int kConfigCacheMs = 700;
constexpr int kDefaultSampleRate = 48000;
constexpr int kMaxChannels = 8;
constexpr int kRingSize = 32768;
constexpr int kDelaySize = 65536;
constexpr int kXfadeSamples = 128;
constexpr int kSoundpadCacheMs = 160;
constexpr const char *kAdbConfigPath = "/data/adb/qwulivoice/config.properties";
constexpr const char *kAppConfigPath = "/data/local/tmp/qwulivoice-com.qwulivoice.beta.properties";
constexpr const char *kVendorConfigPath = "/vendor/etc/qwulivoice.properties";
constexpr const char *kAdbSoundpadLibraryPath = "/data/adb/qwulivoice/soundpad.properties";
constexpr const char *kAdbSoundpadPlaybackPath = "/data/adb/qwulivoice/soundpad.state.properties";
constexpr const char *kAppSoundpadLibraryPath = "/data/local/tmp/qwulivoice-com.qwulivoice.beta.soundpad.properties";
constexpr const char *kAppSoundpadPlaybackPath = "/data/local/tmp/qwulivoice-com.qwulivoice.beta.soundpad.state.properties";
constexpr const char *kAppSoundpadDir = "/data/local/tmp/qwulivoice-com.qwulivoice.beta-soundpad/";
constexpr const char *kAdbSoundpadDir = "/data/adb/qwulivoice/soundpad/";

enum EffectCommand : uint32_t {
    EFFECT_CMD_INIT = 0,
    EFFECT_CMD_SET_CONFIG = 1,
    EFFECT_CMD_RESET = 2,
    EFFECT_CMD_ENABLE = 3,
    EFFECT_CMD_DISABLE = 4,
    EFFECT_CMD_SET_DEVICE = 8,
    EFFECT_CMD_SET_AUDIO_MODE = 10,
    EFFECT_CMD_SET_CONFIG_REVERSE = 11,
    EFFECT_CMD_SET_INPUT_DEVICE = 12,
    EFFECT_CMD_SET_AUDIO_SOURCE = 15,
};

struct effect_uuid_t {
    uint32_t timeLow;
    uint16_t timeMid;
    uint16_t timeHiAndVersion;
    uint16_t clockSeq;
    uint8_t node[6];
};

struct buffer_provider_t {
    void *cookie;
    int32_t (*getBuffer)(void *cookie, void *buffer);
    int32_t (*releaseBuffer)(void *cookie, void *buffer);
};

struct audio_buffer_t {
    size_t frameCount;
    union {
        void *raw;
        int16_t *s16;
        float *f32;
    };
};

struct buffer_config_t {
    audio_buffer_t buffer;
    uint32_t samplingRate;
    uint32_t channels;
    buffer_provider_t bufferProvider;
    uint8_t format;
    uint8_t accessMode;
    uint16_t mask;
};

struct effect_config_t {
    buffer_config_t inputCfg;
    buffer_config_t outputCfg;
};

struct effect_descriptor_t {
    effect_uuid_t type;
    effect_uuid_t uuid;
    uint32_t apiVersion;
    uint32_t flags;
    uint16_t cpuLoad;
    uint16_t memoryUsage;
    char name[64];
    char implementor[64];
};

struct effect_interface_s;
using effect_handle_t = effect_interface_s **;

struct effect_interface_s {
    int32_t (*process)(effect_handle_t self, audio_buffer_t *inBuffer, audio_buffer_t *outBuffer);
    int32_t (*command)(
        effect_handle_t self,
        uint32_t cmdCode,
        uint32_t cmdSize,
        void *pCmdData,
        uint32_t *replySize,
        void *pReplyData);
    int32_t (*get_descriptor)(effect_handle_t self, effect_descriptor_t *pDescriptor);
    int32_t (*process_reverse)(effect_handle_t self, audio_buffer_t *inBuffer, audio_buffer_t *outBuffer);
};

struct audio_effect_library_t {
    uint32_t tag;
    uint32_t version;
    const char *name;
    const char *implementor;
    int32_t (*create_effect)(const effect_uuid_t *uuid, int32_t sessionId, int32_t ioId, effect_handle_t *pHandle);
    int32_t (*release_effect)(effect_handle_t handle);
    int32_t (*get_descriptor)(const effect_uuid_t *uuid, effect_descriptor_t *pDescriptor);
};

struct VoiceConfig {
    bool enabled = false;
    std::string mode = "original";
    int strength = 85;
    int gain = 0;
    int64_t loadedAtMs = 0;
};

struct SoundpadSnapshot {
    bool active = false;
    std::string pcmPath;
    int sampleRate = 48000;
    int mixPercent = 70;
    int gainPercent = 100;
    int64_t sessionId = 0;
    bool looping = false;
    int64_t loadedAtMs = 0;
};

struct DspState {
    int sampleRate = kDefaultSampleRate;
    std::string mode;
    std::vector<float> ring = std::vector<float>(kRingSize, 0.0f);
    std::vector<float> delay = std::vector<float>(kDelaySize, 0.0f);
    std::vector<int16_t> soundpadSamples;
    std::string soundpadPcmPath;
    int soundpadSourceSampleRate = kDefaultSampleRate;
    double soundpadPosition = 0.0;
    int64_t soundpadSessionId = 0;
    int64_t soundpadCompletedSessionId = 0;
    bool soundpadLooping = false;
    bool soundpadActive = false;
    float soundpadMix = 0.0f;
    int64_t written = 0;
    float readPos = 0.0f;
    bool readInitialized = false;
    float xfadeOldPos = 0.0f;
    int xfadeRemaining = 0;
    int delayPos = 0;
    float lowPass = 0.0f;
    float hpPrevInput = 0.0f;
    float hpPrevOutput = 0.0f;
    double phase = 0.0;
    double phase2 = 0.0;
    int sampleHoldCounter = 0;
    float sampleHoldValue = 0.0f;
    uint32_t noiseState = 0x1234567u;
};

struct EffectContext {
    const effect_interface_s *itfe;
    effect_config_t config{};
    bool enabled = false;
    int sessionId = 0;
    int ioId = 0;
    uint32_t inputDevice = 0;
    uint32_t audioSource = 0;
    uint32_t audioMode = 0;
    std::mutex lock;
    DspState state;
};

const effect_uuid_t kTypeUuid = {
    0x9e9c93f4, 0x6f74, 0x4f52, 0x9a82, {0x20, 0x2f, 0xcb, 0x7a, 0x12, 0x01}
};
const effect_uuid_t kEffectUuid = {
    0xb4972f02, 0x3d78, 0x4c59, 0x8d1c, {0x7f, 0x5f, 0x37, 0xf8, 0xaa, 0x52}
};

VoiceConfig gConfig;
SoundpadSnapshot gSoundpad;
std::mutex gConfigLock;
std::mutex gSoundpadLock;

int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

void logLine(int priority, const std::string &message) {
    __android_log_write(priority, kTag, message.c_str());
}

bool uuidEquals(const effect_uuid_t &left, const effect_uuid_t &right) {
    return std::memcmp(&left, &right, sizeof(effect_uuid_t)) == 0;
}

int popcount(uint32_t value) {
    const int count = __builtin_popcount(value);
    return std::clamp(count, 1, kMaxChannels);
}

std::string trim(std::string value) {
    const auto start = value.find_first_not_of(" \t\r\n");
    if (start == std::string::npos) {
        return "";
    }
    const auto end = value.find_last_not_of(" \t\r\n");
    return value.substr(start, end - start + 1);
}

bool readBool(std::string_view value, bool fallback) {
    if (value == "true" || value == "1" || value == "yes") {
        return true;
    }
    if (value == "false" || value == "0" || value == "no") {
        return false;
    }
    return fallback;
}

int readInt(std::string_view value, int fallback) {
    const std::string owned(value);
    char *end = nullptr;
    const long parsed = std::strtol(owned.c_str(), &end, 10);
    if (end == nullptr || *end != '\0') {
        return fallback;
    }
    return static_cast<int>(parsed);
}

int64_t readInt64(std::string_view value, int64_t fallback) {
    const std::string owned(value);
    char *end = nullptr;
    const long long parsed = std::strtoll(owned.c_str(), &end, 10);
    if (end == nullptr || *end != '\0') {
        return fallback;
    }
    return static_cast<int64_t>(parsed);
}

bool loadPropertiesMap(const char *path, std::unordered_map<std::string, std::string> &target) {
    std::ifstream input(path);
    if (!input.is_open()) {
        return false;
    }

    std::string line;
    while (std::getline(input, line)) {
        line = trim(line);
        if (line.empty() || line[0] == '#' || line[0] == '!') {
            continue;
        }
        const auto sep = line.find('=');
        if (sep == std::string::npos) {
            continue;
        }
        const std::string key = trim(line.substr(0, sep));
        const std::string value = trim(line.substr(sep + 1));
        if (!key.empty()) {
            target[key] = value;
        }
    }
    return true;
}

std::string prop(
    const std::unordered_map<std::string, std::string> &properties,
    const std::string &key,
    std::string fallback = "") {
    const auto found = properties.find(key);
    return found == properties.end() ? fallback : found->second;
}

bool loadPropertiesFrom(const char *path, VoiceConfig &target) {
    std::ifstream input(path);
    if (!input.is_open()) {
        return false;
    }

    std::string line;
    while (std::getline(input, line)) {
        line = trim(line);
        if (line.empty() || line[0] == '#' || line[0] == '!') {
            continue;
        }
        const auto sep = line.find('=');
        if (sep == std::string::npos) {
            continue;
        }
        const std::string key = trim(line.substr(0, sep));
        const std::string value = trim(line.substr(sep + 1));
        if (key == "enabled") {
            target.enabled = readBool(value, target.enabled);
        } else if (key == "mode_id") {
            target.mode = value.empty() ? "original" : value;
        } else if (key == "effect_strength") {
            target.strength = std::clamp(readInt(value, target.strength), 0, 100);
        } else if (key == "mic_gain_percent") {
            target.gain = std::clamp(readInt(value, target.gain), 0, 101);
        }
    }
    return true;
}

VoiceConfig loadConfig() {
    const int64_t now = nowMs();
    {
        std::lock_guard<std::mutex> lock(gConfigLock);
        if (now - gConfig.loadedAtMs < kConfigCacheMs) {
            return gConfig;
        }
    }

    VoiceConfig next;
    const bool loaded =
        loadPropertiesFrom(kAdbConfigPath, next) ||
        loadPropertiesFrom(kAppConfigPath, next) ||
        loadPropertiesFrom(kVendorConfigPath, next);
    next.loadedAtMs = now;
    if (!loaded) {
        next.enabled = false;
    }

    std::lock_guard<std::mutex> lock(gConfigLock);
    gConfig = next;
    return gConfig;
}

int64_t javaStringHash(const std::string &value) {
    int32_t hash = 0;
    for (const unsigned char ch : value) {
        hash = (hash * 31) + static_cast<int32_t>(ch);
    }
    return static_cast<int64_t>(hash);
}

bool startsWith(std::string_view value, std::string_view prefix) {
    return value.size() >= prefix.size() && value.substr(0, prefix.size()) == prefix;
}

std::string adbSoundpadPathFor(const std::string &path) {
    if (!startsWith(path, kAppSoundpadDir)) {
        return "";
    }
    return std::string(kAdbSoundpadDir) + path.substr(std::strlen(kAppSoundpadDir));
}

SoundpadSnapshot loadSoundpadSnapshot() {
    const int64_t now = nowMs();
    {
        std::lock_guard<std::mutex> lock(gSoundpadLock);
        if (now - gSoundpad.loadedAtMs < kSoundpadCacheMs) {
            return gSoundpad;
        }
    }

    std::unordered_map<std::string, std::string> playback;
    std::unordered_map<std::string, std::string> library;
    const bool playbackLoaded =
        loadPropertiesMap(kAdbSoundpadPlaybackPath, playback) ||
        loadPropertiesMap(kAppSoundpadPlaybackPath, playback);
    const bool libraryLoaded =
        loadPropertiesMap(kAdbSoundpadLibraryPath, library) ||
        loadPropertiesMap(kAppSoundpadLibraryPath, library);

    SoundpadSnapshot next;
    next.loadedAtMs = now;
    if (!playbackLoaded || !libraryLoaded) {
        std::lock_guard<std::mutex> lock(gSoundpadLock);
        gSoundpad = next;
        return gSoundpad;
    }

    const std::string activeSlot = prop(playback, "active_slot_id");
    const bool playing = readBool(prop(playback, "playing"), false);
    next.looping = readBool(prop(playback, "looping"), false);
    next.mixPercent = std::clamp(readInt(prop(playback, "mix_percent"), 70), 0, 100);
    next.sessionId = readInt64(prop(playback, "session_id"), 0);
    if (!playing || activeSlot.empty() || next.mixPercent <= 0) {
        std::lock_guard<std::mutex> lock(gSoundpadLock);
        gSoundpad = next;
        return gSoundpad;
    }

    const int slotCount = std::clamp(readInt(prop(library, "slot_count"), 0), 0, 512);
    for (int index = 0; index < slotCount; ++index) {
        const std::string prefix = "slot." + std::to_string(index) + ".";
        if (prop(library, prefix + "id") != activeSlot) {
            continue;
        }
        next.pcmPath = prop(library, prefix + "pcm_path");
        next.sampleRate = std::max(readInt(prop(library, prefix + "sample_rate"), kDefaultSampleRate), 8000);
        const int frameCount = readInt(prop(library, prefix + "frame_count"), 0);
        next.gainPercent = std::clamp(readInt(prop(library, prefix + "gain_percent"), 100), 0, 160);
        if (next.sessionId <= 0) {
            next.sessionId = javaStringHash(activeSlot);
        }
        next.active = !next.pcmPath.empty() && frameCount > 0;
        break;
    }

    std::lock_guard<std::mutex> lock(gSoundpadLock);
    gSoundpad = next;
    return gSoundpad;
}

float softClip(float value) {
    return std::clamp(static_cast<float>(std::tanh(value)), -1.0f, 1.0f);
}

void prepareState(DspState &state, int sampleRate, std::string_view mode) {
    const int safeRate = std::max(sampleRate, 8000);
    if (state.sampleRate == safeRate && state.mode == mode) {
        return;
    }
    state = DspState{};
    state.sampleRate = safeRate;
    state.mode = std::string(mode);
}

void writeRing(DspState &state, float input) {
    state.ring[static_cast<size_t>(state.written) & (state.ring.size() - 1)] = input;
    ++state.written;
}

float sampleAt(DspState &state, float position) {
    const int base = static_cast<int>(position);
    const float fraction = position - static_cast<float>(base);
    const auto mask = static_cast<int>(state.ring.size() - 1);
    const float first = state.ring[base & mask];
    const float second = state.ring[(base + 1) & mask];
    return first + ((second - first) * fraction);
}

void startCrossfade(DspState &state, float newReadPos) {
    state.xfadeOldPos = state.readPos;
    state.readPos = newReadPos;
    state.xfadeRemaining = kXfadeSamples;
}

float pitched(DspState &state, float ratio) {
    if (ratio <= 0.0f || state.written <= 2) {
        return 0.0f;
    }
    if (!state.readInitialized) {
        state.readPos = static_cast<float>(state.written) - (state.sampleRate * 0.06f);
        state.readInitialized = true;
    }
    const float minReadable = static_cast<float>(state.written) - static_cast<float>(state.ring.size()) + 32.0f;
    const float maxReadable = std::max(static_cast<float>(state.written) - 16.0f, minReadable);
    if (state.readPos < minReadable) {
        state.readPos = minReadable;
    }

    float sample = sampleAt(state, state.readPos);
    if (state.xfadeRemaining > 0) {
        const float oldSample = sampleAt(state, state.xfadeOldPos);
        const float blend = static_cast<float>(kXfadeSamples - state.xfadeRemaining) / static_cast<float>(kXfadeSamples);
        sample = (sample * blend) + (oldSample * (1.0f - blend));
        state.xfadeOldPos += ratio;
        --state.xfadeRemaining;
    }

    state.readPos += ratio;
    if (state.readPos > maxReadable) {
        startCrossfade(state, maxReadable - (state.sampleRate * 0.035f));
    } else if (state.readPos < minReadable) {
        startCrossfade(state, minReadable + (state.sampleRate * 0.02f));
    }
    return sample;
}

float highPass(DspState &state, float input, float coefficient) {
    const float output = coefficient * ((state.hpPrevOutput + input) - state.hpPrevInput);
    state.hpPrevOutput = output;
    state.hpPrevInput = input;
    return output;
}

float lowPass(DspState &state, float input, float alpha) {
    state.lowPass += alpha * (input - state.lowPass);
    return state.lowPass;
}

float brighten(DspState &state, float input, float amount) {
    return softClip((input * (1.0f - amount)) + (highPass(state, input, 0.975f) * amount * 1.8f));
}

float darken(DspState &state, float input, float alpha, float gain) {
    return softClip(lowPass(state, input, alpha) * gain);
}

float osc(DspState &state, double &phase, float hz) {
    phase += (hz * 2.0 * kPi) / static_cast<double>(std::max(state.sampleRate, 8000));
    if (phase > 2.0 * kPi) {
        phase -= 2.0 * kPi;
    }
    return static_cast<float>(std::sin(phase));
}

float nextNoise(DspState &state) {
    const uint32_t step1 = state.noiseState ^ (state.noiseState << 13);
    const uint32_t step2 = step1 ^ (step1 >> 17);
    state.noiseState = step2 ^ (step2 << 5);
    return static_cast<float>(state.noiseState & 0x7fffffffu) / 1073741800.0f - 1.0f;
}

float sampleHold(DspState &state, float input, int samples) {
    if (state.sampleHoldCounter <= 0) {
        state.sampleHoldCounter = samples;
        state.sampleHoldValue = input;
    } else {
        --state.sampleHoldCounter;
    }
    return state.sampleHoldValue;
}

float readDelay(DspState &state, int samples) {
    const int delaySamples = std::clamp(samples, 1, static_cast<int>(state.delay.size()) - 1);
    int index = state.delayPos - delaySamples;
    while (index < 0) {
        index += static_cast<int>(state.delay.size());
    }
    return state.delay[static_cast<size_t>(index) % state.delay.size()];
}

void writeDelay(DspState &state, float input) {
    state.delay[static_cast<size_t>(state.delayPos)] = input;
    state.delayPos = (state.delayPos + 1) % static_cast<int>(state.delay.size());
}

float echo(DspState &state, float input, float seconds, float feedback, float mix) {
    const float delayed = readDelay(state, static_cast<int>(state.sampleRate * seconds));
    writeDelay(state, softClip(input + (delayed * feedback)));
    return softClip(input + (delayed * mix));
}

float applyMicBoost(float input, int boost) {
    const float amount = static_cast<float>(std::clamp(boost, 0, 101));
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
        const std::string adbPath = adbSoundpadPathFor(path);
        if (!adbPath.empty()) {
            file = std::fopen(adbPath.c_str(), "rb");
        }
    }
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

bool prepareSoundpadRuntime(DspState &state, const SoundpadSnapshot &soundpad) {
    if (!soundpad.active || soundpad.pcmPath.empty() || soundpad.sampleRate <= 0) {
        state.soundpadActive = false;
        state.soundpadMix = 0.0f;
        return false;
    }

    const bool needsReload =
        soundpad.sessionId != state.soundpadSessionId ||
        soundpad.pcmPath != state.soundpadPcmPath;

    if (state.soundpadCompletedSessionId == soundpad.sessionId &&
        state.soundpadPcmPath == soundpad.pcmPath &&
        !soundpad.looping) {
        state.soundpadActive = false;
        state.soundpadMix = 0.0f;
        return false;
    }

    if (needsReload) {
        state.soundpadSamples = loadPcmFile(soundpad.pcmPath);
        state.soundpadPcmPath = soundpad.pcmPath;
        state.soundpadSourceSampleRate = std::max(soundpad.sampleRate, 8000);
        state.soundpadPosition = 0.0;
        state.soundpadSessionId = soundpad.sessionId;
        state.soundpadCompletedSessionId = 0;
        state.soundpadActive = !state.soundpadSamples.empty();
    }

    state.soundpadLooping = soundpad.looping;
    state.soundpadMix = computeSoundpadMix(soundpad.mixPercent, soundpad.gainPercent);
    if (state.soundpadSamples.empty()) {
        state.soundpadActive = false;
    }
    return state.soundpadActive && state.soundpadMix > 0.0f;
}

float nextSoundpadSample(int outputSampleRate, DspState &state) {
    if (!state.soundpadActive || state.soundpadSamples.empty()) {
        return 0.0f;
    }

    const int frameCount = static_cast<int>(state.soundpadSamples.size());
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

float processMode(float input, const VoiceConfig &config, DspState &state) {
    const std::string &mode = config.mode;
    const float strength = std::clamp(config.strength, 0, 100) / 100.0f;
    writeRing(state, input);

    float effected = input;
    if (mode == "speed") {
        effected = brighten(state, pitched(state, 1.12f), 0.24f);
    } else if (mode == "robot") {
        effected = softClip(std::round(input * ((osc(state, state.phase, 78.0f) * 0.65f) + 0.35f) * 24.0f) / 24.0f * 1.35f);
    } else if (mode == "alien") {
        const float mod = (((osc(state, state.phase, 33.0f) * 0.6f) + (osc(state, state.phase2, 91.0f) * 0.4f)) * 0.55f) + 0.45f;
        effected = softClip(sampleHold(state, pitched(state, 1.22f) * mod, 3) * 1.25f);
    } else if (mode == "hoarse") {
        effected = softClip(highPass(state, input + (nextNoise(state) * 0.1f * std::abs(0.08f + input)), 0.986f) * 1.7f);
    } else if (mode == "noise") {
        effected = softClip(highPass(state, input + (nextNoise(state) * 0.18f), 0.972f) * 1.16f);
    } else if (mode == "child") {
        effected = brighten(state, pitched(state, 1.28f), 0.30f);
    } else if (mode == "mouse") {
        effected = brighten(state, pitched(state, 1.52f), 0.42f);
    } else if (mode == "male" || mode == "deep") {
        effected = darken(state, pitched(state, 0.84f), 0.08f, 1.28f);
    } else if (mode == "female") {
        effected = brighten(state, pitched(state, 1.08f), 0.18f);
    } else if (mode == "monster") {
        effected = darken(state, pitched(state, 0.64f), 0.05f, 1.85f);
    } else if (mode == "echo") {
        effected = echo(state, input, 0.18f, 0.45f, 0.52f);
    } else if (mode == "helium" || mode == "bright") {
        effected = brighten(state, pitched(state, 1.42f), 0.26f);
    } else if (mode == "purr") {
        effected = brighten(state, pitched(state, 1.42f), 0.36f);
    } else if (mode == "hexafluoride") {
        effected = darken(state, pitched(state, 0.56f), 0.04f, 1.95f);
    } else if (mode == "cave") {
        const float delay1 = readDelay(state, static_cast<int>(state.sampleRate * 0.11f));
        const float delay2 = readDelay(state, static_cast<int>(state.sampleRate * 0.23f));
        const float delay3 = readDelay(state, static_cast<int>(state.sampleRate * 0.41f));
        const float mid = delay2 * 0.22f;
        writeDelay(state, softClip(input + (delay1 * 0.42f) + mid));
        effected = softClip((input * 0.7f) + (delay1 * 0.38f) + mid + (delay3 * 0.14f));
    } else if (mode == "custom") {
        const float semitones = ((std::clamp(config.strength, 0, 100) - 50) / 50.0f) * 12.0f;
        const float ratio = std::pow(2.0f, semitones / 12.0f);
        const float shifted = pitched(state, ratio);
        effected = ratio >= 1.0f ? brighten(state, shifted, 0.22f) : darken(state, shifted, 0.08f, 1.18f);
    }

    const float wet = (mode == "custom" || mode == "purr") ? 1.0f : strength;
    const float mixed = (input * (1.0f - wet)) + (effected * wet);
    return applyMicBoost(mixed, config.gain);
}

EffectContext *contextFromHandle(effect_handle_t handle) {
    if (handle == nullptr) {
        return nullptr;
    }
    return reinterpret_cast<EffectContext *>(handle);
}

uint8_t resolveFormat(const EffectContext &ctx) {
    if (ctx.config.outputCfg.format != 0) {
        return ctx.config.outputCfg.format;
    }
    return ctx.config.inputCfg.format != 0 ? ctx.config.inputCfg.format : kAudioFormatPcm16;
}

int resolveSampleRate(const EffectContext &ctx) {
    if (ctx.config.outputCfg.samplingRate > 0) {
        return static_cast<int>(ctx.config.outputCfg.samplingRate);
    }
    if (ctx.config.inputCfg.samplingRate > 0) {
        return static_cast<int>(ctx.config.inputCfg.samplingRate);
    }
    return kDefaultSampleRate;
}

int resolveChannels(const EffectContext &ctx) {
    if (ctx.config.outputCfg.channels > 0) {
        return popcount(ctx.config.outputCfg.channels);
    }
    if (ctx.config.inputCfg.channels > 0) {
        return popcount(ctx.config.inputCfg.channels);
    }
    return 1;
}

void passthrough(audio_buffer_t *inBuffer, audio_buffer_t *outBuffer, uint8_t format, int channels) {
    if (inBuffer == nullptr || outBuffer == nullptr || inBuffer->raw == nullptr || outBuffer->raw == nullptr) {
        return;
    }
    if (inBuffer->raw == outBuffer->raw) {
        return;
    }
    const size_t bytesPerSample = format == kAudioFormatPcmFloat ? sizeof(float) : sizeof(int16_t);
    const size_t frames = std::min(inBuffer->frameCount, outBuffer->frameCount);
    std::memcpy(outBuffer->raw, inBuffer->raw, frames * static_cast<size_t>(channels) * bytesPerSample);
}

int32_t effectProcess(effect_handle_t self, audio_buffer_t *inBuffer, audio_buffer_t *outBuffer) {
    EffectContext *ctx = contextFromHandle(self);
    if (ctx == nullptr || inBuffer == nullptr || outBuffer == nullptr || inBuffer->raw == nullptr || outBuffer->raw == nullptr) {
        return -EINVAL;
    }

    const VoiceConfig config = loadConfig();
    const SoundpadSnapshot soundpad = loadSoundpadSnapshot();
    const uint8_t format = resolveFormat(*ctx);
    const int sampleRate = resolveSampleRate(*ctx);
    const int channels = resolveChannels(*ctx);
    const size_t frames = std::min(inBuffer->frameCount, outBuffer->frameCount);
    if (!ctx->enabled || (!config.enabled && !soundpad.active) || frames == 0) {
        passthrough(inBuffer, outBuffer, format, channels);
        return 0;
    }

    std::lock_guard<std::mutex> lock(ctx->lock);
    prepareState(ctx->state, sampleRate, config.enabled ? config.mode : "original");
    const bool applySoundpad = prepareSoundpadRuntime(ctx->state, soundpad);

    if (format == kAudioFormatPcmFloat) {
        for (size_t frame = 0; frame < frames; ++frame) {
            const float pad = applySoundpad ? nextSoundpadSample(sampleRate, ctx->state) : 0.0f;
            for (int channel = 0; channel < channels; ++channel) {
                const size_t index = (frame * static_cast<size_t>(channels)) + static_cast<size_t>(channel);
                const float input = std::clamp(inBuffer->f32[index], -1.0f, 1.0f);
                const float voice = config.enabled ? processMode(input, config, ctx->state) : input;
                outBuffer->f32[index] = applySoundpad ? softClip(voice + pad) : voice;
            }
        }
        return 0;
    }

    if (format != kAudioFormatPcm16) {
        passthrough(inBuffer, outBuffer, format, channels);
        return 0;
    }

    for (size_t frame = 0; frame < frames; ++frame) {
        const float pad = applySoundpad ? nextSoundpadSample(sampleRate, ctx->state) : 0.0f;
        for (int channel = 0; channel < channels; ++channel) {
            const size_t index = (frame * static_cast<size_t>(channels)) + static_cast<size_t>(channel);
            const float input = static_cast<float>(inBuffer->s16[index]) / 32768.0f;
            const float voice = config.enabled ? processMode(input, config, ctx->state) : input;
            const float output = applySoundpad ? softClip(voice + pad) : voice;
            const int packed = static_cast<int>(std::lrint(output * 32767.0f));
            outBuffer->s16[index] = static_cast<int16_t>(std::clamp(packed, -32768, 32767));
        }
    }
    return 0;
}

void setReply(uint32_t *replySize, void *replyData, int32_t status) {
    if (replySize != nullptr && replyData != nullptr && *replySize >= sizeof(int32_t)) {
        *reinterpret_cast<int32_t *>(replyData) = status;
        *replySize = sizeof(int32_t);
    }
}

int32_t effectCommand(
    effect_handle_t self,
    uint32_t cmdCode,
    uint32_t cmdSize,
    void *pCmdData,
    uint32_t *replySize,
    void *pReplyData) {
    EffectContext *ctx = contextFromHandle(self);
    if (ctx == nullptr) {
        return -EINVAL;
    }

    switch (cmdCode) {
        case EFFECT_CMD_INIT:
            ctx->enabled = false;
            setReply(replySize, pReplyData, 0);
            return 0;
        case EFFECT_CMD_ENABLE:
            ctx->enabled = true;
            setReply(replySize, pReplyData, 0);
            return 0;
        case EFFECT_CMD_DISABLE:
            ctx->enabled = false;
            setReply(replySize, pReplyData, 0);
            return 0;
        case EFFECT_CMD_RESET:
            {
                std::lock_guard<std::mutex> lock(ctx->lock);
                ctx->state = DspState{};
            }
            return 0;
        case EFFECT_CMD_SET_CONFIG:
        case EFFECT_CMD_SET_CONFIG_REVERSE:
            if (pCmdData == nullptr || cmdSize < sizeof(effect_config_t)) {
                setReply(replySize, pReplyData, -EINVAL);
                return -EINVAL;
            }
            ctx->config = *reinterpret_cast<effect_config_t *>(pCmdData);
            setReply(replySize, pReplyData, 0);
            return 0;
        case EFFECT_CMD_SET_INPUT_DEVICE:
        case EFFECT_CMD_SET_DEVICE:
            if (pCmdData != nullptr && cmdSize >= sizeof(uint32_t)) {
                ctx->inputDevice = *reinterpret_cast<uint32_t *>(pCmdData);
            }
            return 0;
        case EFFECT_CMD_SET_AUDIO_SOURCE:
            if (pCmdData != nullptr && cmdSize >= sizeof(uint32_t)) {
                ctx->audioSource = *reinterpret_cast<uint32_t *>(pCmdData);
            }
            return 0;
        case EFFECT_CMD_SET_AUDIO_MODE:
            if (pCmdData != nullptr && cmdSize >= sizeof(uint32_t)) {
                ctx->audioMode = *reinterpret_cast<uint32_t *>(pCmdData);
            }
            return 0;
        default:
            setReply(replySize, pReplyData, 0);
            return 0;
    }
}

int32_t effectGetDescriptor(effect_handle_t, effect_descriptor_t *pDescriptor);
int32_t effectProcessReverse(effect_handle_t, audio_buffer_t *, audio_buffer_t *) {
    return 0;
}

const effect_interface_s kInterface = {
    effectProcess,
    effectCommand,
    effectGetDescriptor,
    effectProcessReverse,
};

effect_descriptor_t makeDescriptor() {
    effect_descriptor_t descriptor{};
    descriptor.type = kTypeUuid;
    descriptor.uuid = kEffectUuid;
    descriptor.apiVersion = kEffectApiVersion;
    descriptor.flags = kEffectFlagTypePreProc | kEffectFlagDeviceInd | kEffectFlagAudioModeInd | kEffectFlagAudioSourceInd;
    descriptor.cpuLoad = 48;
    descriptor.memoryUsage = 96;
    std::snprintf(descriptor.name, sizeof(descriptor.name), "qwulivoice root preprocess");
    std::snprintf(descriptor.implementor, sizeof(descriptor.implementor), "@qwulise");
    return descriptor;
}

int32_t effectGetDescriptor(effect_handle_t, effect_descriptor_t *pDescriptor) {
    if (pDescriptor == nullptr) {
        return -EINVAL;
    }
    *pDescriptor = makeDescriptor();
    return 0;
}

int32_t createEffect(const effect_uuid_t *uuid, int32_t sessionId, int32_t ioId, effect_handle_t *pHandle) {
    if (uuid == nullptr || pHandle == nullptr) {
        return -EINVAL;
    }
    if (!uuidEquals(*uuid, kEffectUuid)) {
        return -ENOENT;
    }
    auto *ctx = new (std::nothrow) EffectContext();
    if (ctx == nullptr) {
        return -ENOMEM;
    }
    ctx->itfe = &kInterface;
    ctx->sessionId = sessionId;
    ctx->ioId = ioId;
    ctx->config.inputCfg.samplingRate = kDefaultSampleRate;
    ctx->config.outputCfg.samplingRate = kDefaultSampleRate;
    ctx->config.inputCfg.channels = 1;
    ctx->config.outputCfg.channels = 1;
    ctx->config.inputCfg.format = kAudioFormatPcm16;
    ctx->config.outputCfg.format = kAudioFormatPcm16;
    *pHandle = reinterpret_cast<effect_handle_t>(ctx);
    logLine(ANDROID_LOG_INFO, "created root pre-processing effect");
    return 0;
}

int32_t releaseEffect(effect_handle_t handle) {
    EffectContext *ctx = contextFromHandle(handle);
    if (ctx == nullptr) {
        return -EINVAL;
    }
    delete ctx;
    return 0;
}

int32_t getDescriptor(const effect_uuid_t *uuid, effect_descriptor_t *pDescriptor) {
    if (uuid == nullptr || pDescriptor == nullptr) {
        return -EINVAL;
    }
    if (!uuidEquals(*uuid, kEffectUuid)) {
        return -ENOENT;
    }
    *pDescriptor = makeDescriptor();
    return 0;
}

}  // namespace

extern "C" __attribute__((visibility("default"))) audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM = {
    kAudioEffectLibraryTag,
    kEffectLibraryApiVersion,
    "qwulivoice root audio",
    "@qwulise",
    createEffect,
    releaseEffect,
    getDescriptor,
};
