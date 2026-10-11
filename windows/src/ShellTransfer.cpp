#include "ShellTransfer.h"
#include <shlobj.h>
#include <objbase.h>
#include <cstring>
#include <stdexcept>
#include <memory>

namespace shelltransfer {
namespace {
constexpr uint32_t Magic = 0x3146424C; // LBF1
void Require(bool condition, const char* message) { if(!condition) throw std::runtime_error(message); }
struct FileHandle {
    HANDLE value = INVALID_HANDLE_VALUE;
    ~FileHandle() { if(value != INVALID_HANDLE_VALUE) CloseHandle(value); }
};
std::filesystem::path RequestDirectory() {
    PWSTR raw = nullptr;
    Require(SUCCEEDED(SHGetKnownFolderPath(FOLDERID_LocalAppData,0,nullptr,&raw)),"Cannot find local app data");
    std::filesystem::path path;
    try { path = std::filesystem::path(raw)/L"LanBridge"/L"ShellRequests"; }
    catch(...) { CoTaskMemFree(raw); throw; }
    CoTaskMemFree(raw);
    return path;
}
void Append(std::vector<unsigned char>& bytes, uint32_t value) {
    auto old = bytes.size(); bytes.resize(old+sizeof(value));
    memcpy(bytes.data()+old,&value,sizeof(value));
}
}
std::vector<unsigned char> Encode(const Paths& paths) {
    Require(!paths.empty() && paths.size() <= MaxFiles,"Select 1 to 1024 files");
    std::vector<unsigned char> bytes;
    Append(bytes,Magic); Append(bytes,static_cast<uint32_t>(paths.size()));
    for(const auto& path:paths) {
        auto text = path.wstring();
        Require(path.is_absolute() && !text.empty() && text.size() < 32768 && text.find(L'\0') == std::wstring::npos,"Invalid file path");
        Require(bytes.size()+4+text.size()*sizeof(wchar_t) <= MaxPayload,"Too many selected paths");
        Append(bytes,static_cast<uint32_t>(text.size()));
        auto start = bytes.size(); bytes.resize(start+text.size()*sizeof(wchar_t));
        memcpy(bytes.data()+start,text.data(),text.size()*sizeof(wchar_t));
    }
    return bytes;
}
Paths Decode(const void* payload, size_t size) {
    Require(payload && size >= 8 && size <= MaxPayload,"Invalid share request size");
    auto* bytes = static_cast<const unsigned char*>(payload);
    size_t offset = 0;
    auto number = [&] {
        Require(size-offset >= sizeof(uint32_t),"Truncated share request");
        uint32_t result; memcpy(&result,bytes+offset,sizeof(result)); offset += sizeof(result); return result;
    };
    Require(number() == Magic,"Invalid share request version");
    auto count = number(); Require(count > 0 && count <= MaxFiles,"Invalid file count");
    Paths paths; paths.reserve(count);
    for(uint32_t i=0;i<count;i++) {
        auto length = number();
        Require(length > 0 && length < 32768 && static_cast<size_t>(length)*sizeof(wchar_t) <= size-offset,"Invalid path length");
        std::wstring text(length,L'\0'); memcpy(text.data(),bytes+offset,length*sizeof(wchar_t)); offset += length*sizeof(wchar_t);
        std::filesystem::path path(text);
        Require(text.find(L'\0') == std::wstring::npos && path.is_absolute(),"Invalid file path");
        paths.push_back(std::move(path));
    }
    Require(offset == size,"Unexpected share request data");
    return paths;
}
std::wstring QuoteArgument(const std::wstring& argument) {
    std::wstring result = L"\""; size_t slashes = 0;
    for(auto ch:argument) {
        if(ch == L'\\') { ++slashes; continue; }
        if(ch == L'\"') { result.append(slashes*2+1,L'\\'); result += ch; }
        else { result.append(slashes,L'\\'); result += ch; }
        slashes = 0;
    }
    result.append(slashes*2,L'\\'); result += L'\"'; return result;
}
std::filesystem::path WriteRequest(const Paths& paths) {
    auto bytes = Encode(paths); auto directory = RequestDirectory();
    std::filesystem::create_directories(directory);
    GUID id{}; Require(SUCCEEDED(CoCreateGuid(&id)),"Cannot create share ID");
    wchar_t text[40]{}; StringFromGUID2(id,text,40);
    auto path = directory/(L"share-"+std::wstring(text)+L".bin");
    bool success = false;
    {
        FileHandle file{CreateFileW(path.c_str(),GENERIC_WRITE,0,nullptr,CREATE_NEW,FILE_ATTRIBUTE_TEMPORARY,nullptr)};
        Require(file.value != INVALID_HANDLE_VALUE,"Cannot create share request");
        DWORD written = 0; success = WriteFile(file.value,bytes.data(),static_cast<DWORD>(bytes.size()),&written,nullptr) && written == bytes.size();
    }
    if(!success) { DeleteFileW(path.c_str()); throw std::runtime_error("Cannot write share request"); }
    return path;
}
Paths ReadRequest(const std::filesystem::path& request) {
    auto path = request.lexically_normal(); auto directory = RequestDirectory();
    auto parent = path.parent_path().wstring(), expected = directory.wstring(), name = path.filename().wstring();
    Require(CompareStringOrdinal(parent.c_str(),-1,expected.c_str(),-1,TRUE) == CSTR_EQUAL &&
        name.starts_with(L"share-{") && name.ends_with(L"}.bin") && name.size() == 48,"Invalid request location");
    std::vector<unsigned char> bytes;
    {
        FileHandle file{CreateFileW(path.c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,nullptr)};
        Require(file.value != INVALID_HANDLE_VALUE,"Cannot open share request");
        BY_HANDLE_FILE_INFORMATION info{};
        Require(GetFileInformationByHandle(file.value,&info) && !(info.dwFileAttributes & (FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT)),"Invalid request file");
        LARGE_INTEGER length{};
        Require(GetFileSizeEx(file.value,&length) && length.QuadPart >= 8 && length.QuadPart <= MaxPayload,"Invalid request file size");
        bytes.resize(static_cast<size_t>(length.QuadPart)); DWORD read = 0;
        Require(ReadFile(file.value,bytes.data(),static_cast<DWORD>(bytes.size()),&read,nullptr) && read == bytes.size(),"Cannot read share request");
    }
    auto paths = Decode(bytes.data(),bytes.size());
    Require(DeleteFileW(path.c_str()) != FALSE,"Cannot remove consumed share request");
    return paths;
}
bool Forward(HWND window, const Paths& paths) {
    auto bytes = Encode(paths);
    COPYDATASTRUCT request{MessageId,static_cast<DWORD>(bytes.size()),bytes.data()}; DWORD_PTR result = 0;
    return SendMessageTimeoutW(window,WM_COPYDATA,0,reinterpret_cast<LPARAM>(&request),SMTO_ABORTIFHUNG,5000,&result) != 0 && result == 1;
}
void Launch(const std::filesystem::path& executable, const Paths& paths) {
    auto request = WriteRequest(paths);
    auto command = QuoteArgument(executable.wstring())+L" --send-request "+QuoteArgument(request.wstring());
    STARTUPINFOW startup{sizeof(startup)}; PROCESS_INFORMATION process{};
    if(!CreateProcessW(executable.c_str(),command.data(),nullptr,nullptr,FALSE,0,nullptr,executable.parent_path().c_str(),&startup,&process)) {
        DeleteFileW(request.c_str()); throw std::runtime_error("Cannot start LanBridge");
    }
    CloseHandle(process.hThread); CloseHandle(process.hProcess);
}
}
