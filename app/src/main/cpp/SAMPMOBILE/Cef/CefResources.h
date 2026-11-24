#pragma once

// Resource channel (stage 5): turns the omp-cef download protocol into plain
// files under <filesDir>/cef/resources/<resource>/<path>, which is exactly the
// tree CefResourceProvider (Java) serves for http://cef/<resource>/<path>.
//
// Mirrors src/client/core/system/resource_manager.cpp:
//
//   JoinResponse.manifest_json  -> { "<resource>": [ {path,hash,size}, ... ] }
//   ServerConfig.master_resource_key
//   client: RequestFiles([resource, path])        <- only files failing the
//                                                   local SHA-256 cache check
//   server: FileData(chunkIndex/totalChunks/data)  <- 1200-byte chunks
//   client: verify SHA-256 against the *manifest*, store the .pak, unzip every
//           entry, AES-128-CBC decrypt it (OCEF2), write plaintext to disk
//   client: DownloadComplete                       <- once, when nothing is left
//
// Everything runs on the game thread (CefSession::Tick / packet dispatch), the
// same thread the PC client uses for its ResourceManager.

#include <cstdint>
#include <functional>
#include <map>
#include <string>
#include <vector>

#include "CefProtocol.h"

namespace cefres
{

class CefResources
{
public:
    static CefResources& Get();

    // True when the sender returned true and the packet is on the wire.
    using Sender = std::function<bool(const cefproto::NetworkPacket&)>;
    // Fired once the resource tree is usable (all downloads done, or nothing
    // to download). CefBrowser replays its queued CreateBrowser events here.
    using ReadyHandler = std::function<void()>;

    void SetSender(Sender sender);
    void SetReadyHandler(ReadyHandler handler);

    // <filesDir>/cef ; empty until CefManager hands it over from Java.
    void SetStorageRoot(const std::string& root);
    std::string ResourceRoot() const;

    // <host>_<port> cache bucket, matching the PC client's naming.
    void SetServerIdentity(const std::string& host, uint16_t port);

    void OnJoinResponse(const std::string& manifestJson);
    void OnServerConfig(const std::vector<uint8_t>& masterKey);
    void OnFileData(const cefproto::FileDataPacket& packet);
    void OnSessionLost();

    // Game thread, every frame: kicks the download off once manifest + key +
    // transport are all available, and reports stalled transfers.
    void Tick();

    bool IsReady() const { return state_ == State::Completed; }
    const char* StateName() const;
    size_t DownloadedFileCount() const { return completedFiles_; }

private:
    CefResources() = default;

    enum class State
    {
        Idle,             // no session / after reset
        AwaitingTrigger,  // manifest received, waiting for key + transport
        VerifyingCache,   // walking the manifest, hashing local .pak files
        Downloading,      // RequestFiles sent, FileData arriving
        Completed,        // resource tree usable
    };

    struct ExpectedFile
    {
        std::string resourceName;
        std::string relativePath;
        std::string hash;
        size_t size = 0;
        std::string savePath;     // <cache>/<relativePath>
        std::string extractDir;   // <resources>/<resourceName>
    };

    struct Expected
    {
        std::string hash;
        size_t size = 0;
        size_t receivedBytes = 0;
        uint32_t totalChunks = 0;
        uint32_t receivedChunks = 0;
        std::vector<std::vector<uint8_t>> chunks;
    };

    void MarkReadyToDownload();
    void TriggerDownload();
    bool VerifyAndExtract(const ExpectedFile& file, const std::string& pakPath);
    bool ExtractPak(const ExpectedFile& file, const std::vector<uint8_t>& pak);
    void CompleteIfDone();
    void ResetState();
    void SendDownloadComplete();
    void SendClientEvent(const char* name);

    State state_ = State::Idle;
    Sender sender_;
    ReadyHandler readyHandler_;

    std::string storageRoot_;
    std::string serverId_ = "unknown_0";
    std::string cacheDir_;

    std::string manifestJson_;
    std::vector<ExpectedFile> requested_;
    std::map<std::string, ExpectedFile> expected_;
    std::map<std::string, Expected> assembling_;

    std::vector<uint8_t> masterKey_;
    bool downloadCompleteSent_ = false;
    size_t completedFiles_ = 0;
    size_t totalFiles_ = 0;
    uint64_t lastActivityMs_ = 0;
    uint64_t lastStallLogMs_ = 0;
};

} // namespace cefres
