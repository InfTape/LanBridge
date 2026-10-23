#pragma once
#include "Bridge.h"

namespace pairing {
std::filesystem::path CredentialPath();
std::string LoadOrCreate(const std::filesystem::path& path);
void Save(const std::filesystem::path& path, const std::string& code);
bridge::Bytes DiscoveryReply(const bridge::Bytes& request, uint16_t port, const std::string& code);
class Discovery {
public:
    Discovery();
    ~Discovery();
    void Start(const std::string& code, uint16_t transferPort=42831, uint16_t discoveryPort=42832);
    void Stop();
private:
    std::thread thread_;
    std::atomic<bool> stopping_{false};
};
}
