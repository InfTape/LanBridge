#pragma once
#include <winsock2.h>
#include <windows.h>
#include <array>
#include <atomic>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <condition_variable>

namespace bridge {
using Bytes = std::vector<unsigned char>;
std::string Utf8(const std::wstring& s);
std::wstring Wide(const std::string& s);
std::string RandomCode();
struct LocalAddress { std::wstring address; bool autoSelect; };
std::vector<LocalAddress> LocalAddresses();
std::filesystem::path ReceiveDirectory();
bool CopyText(HWND owner, const std::wstring& text);
bool CopyFiles(HWND owner, const std::vector<std::filesystem::path>& files);
std::wstring ReadText(HWND owner);
std::vector<std::filesystem::path> ReadFiles(HWND owner);
// Snapshot a clipboard bitmap as PNG; the last owner removes the temporary file.
std::shared_ptr<const std::filesystem::path> ReadImage(HWND owner);

// Encrypted protocol shared with Android; independent read/write sequence counters.
class Channel {
public:
    Channel(SOCKET socket, const std::string& code, const Bytes& salt, bool server);
    ~Channel();
    void Send(unsigned char type, const Bytes& data = {});
    std::pair<unsigned char, Bytes> Receive();
    void Close();
private:
    SOCKET socket_;
    void* algorithm_ = nullptr;
    void* key_ = nullptr;
    Bytes keyObject_;
    std::mutex writeMutex_;
    uint64_t tx_ = 0, rx_ = 0;
    bool server_;
    std::atomic<bool> closed_{false};
};
struct Event {
    enum Kind { Connected, Disconnected, Text, File, Progress, Info, Error, Ack, Folder, FolderProgress } kind;
    std::wstring message;
    std::filesystem::path path{};
    uint64_t current = 0, total = 0;
};
class Server {
public:
    using Callback = std::function<void(Event)>;
    Server(std::filesystem::path directory, Callback callback);
    ~Server();
    void Start(uint16_t port, const std::string& code);
    void Stop();
    bool IsConnected() const;
    void SendText(const std::wstring& text);
    void SendFiles(const std::vector<std::filesystem::path>& files);
    void Acknowledge(const std::wstring& message);
    void Disconnect();
private:
    void Run(uint16_t port, std::string code);
    void Session(const std::shared_ptr<Channel>& c);
    std::shared_ptr<Channel> Active() const;
    void Emit(Event e);
    std::filesystem::path directory_;
    Callback callback_;
    std::thread thread_;
    std::atomic<bool> stopping_{false}, authenticated_{false};
    std::atomic<SOCKET> listener_{INVALID_SOCKET}, pending_{INVALID_SOCKET};
    mutable std::mutex mutex_;
    std::mutex transferMutex_;
    std::atomic<bool> peerFolders_{false};
    std::mutex replyMutex_;
    std::condition_variable replyCv_;
    uint64_t awaitingFolder_=0;
    unsigned char awaitingPhase_=0;
    bool replyReceived_=false, replyAccepted_=false;
    std::string replyReason_;
    void FolderReply(unsigned char type, const Bytes& data);
    void ExpectFolderReply(uint64_t id, unsigned char phase);
    void WaitFolderReply();
    std::shared_ptr<Channel> channel_;
};
}
