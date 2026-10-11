#pragma once
#include "Bridge.h"
#include <set>

namespace bridge::folders {
constexpr size_t MaxEntries = 10000, MaxDepth = 64, MaxPathBytes = 4096;
constexpr uint64_t MaxFile = 100ULL*1024*1024*1024, MaxTotal = 100ULL*1024*1024*1024*1024;
constexpr const char* Capability = "LB-FOLDER1";
struct Header { uint64_t id; std::string name; uint32_t files, directories; uint64_t bytes; };
struct Entry { std::filesystem::path source; std::string relative; bool directory; uint64_t size; };
struct Plan { std::string name; std::vector<Entry> entries; uint32_t files=0, directories=0; uint64_t bytes=0; };
std::filesystem::path RelativePath(const std::string& text);
std::wstring PathKey(const std::string& text);
Plan Collect(const std::filesystem::path& root);
void Recover(const std::filesystem::path& destination);
Bytes EncodeHeader(const Header& header);
Header DecodeHeader(const Bytes& bytes);
Bytes FileHeader(const std::string& relative, uint64_t size);
Bytes Reply(uint64_t id, bool accepted, const std::string& reason = {});
uint64_t Id(const Bytes& bytes);
bool Accepted(const Bytes& bytes);
std::string Reason(const Bytes& bytes);
// One unpublished transaction. Destruction removes only its own staging tree.
class Receiver {
public:
    Receiver(const std::filesystem::path& destination, const Header& header);
    ~Receiver();
    void Consume(unsigned char type, const Bytes& bytes);
    std::filesystem::path Commit();
    const Header& Metadata() const { return header_; }
    uint64_t Done() const { return done_; }
private:
    struct File;
    Header header_;
    std::filesystem::path destination_, staging_;
    std::wstring receipt_;
    std::unique_ptr<File> file_;
    std::set<std::wstring> names_, directories_{L""};
    uint32_t filesDone_=0, dirsDone_=0;
    uint64_t done_=0;
    void Register(const std::string& path, bool directory);
};
void Send(Channel& channel, const Plan& plan, const std::function<void(uint64_t,uint64_t)>& progress);
}
