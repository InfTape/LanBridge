#include "Pairing.h"
#include <bcrypt.h>
#include <dpapi.h>
#include <shlobj.h>
#include <ws2tcpip.h>
#include <fstream>
#include <stdexcept>

namespace pairing {
namespace {
bool Valid(const std::string& code) {
    return code.size()==16 && code.find_first_not_of("0123456789ABCDEF")==std::string::npos;
}
struct Blob {
    DATA_BLOB data{};
    ~Blob() { if(data.pbData) { SecureZeroMemory(data.pbData,data.cbData); LocalFree(data.pbData); } }
};
}
std::filesystem::path CredentialPath() {
    PWSTR value=nullptr;
    if(FAILED(SHGetKnownFolderPath(FOLDERID_LocalAppData,0,nullptr,&value))) throw std::runtime_error("Cannot find local pairing storage");
    std::filesystem::path path(value); CoTaskMemFree(value);
    return path/L"LanBridge"/L"pairing.dat";
}
void Save(const std::filesystem::path& path,const std::string& code) {
    if(!Valid(code)) throw std::runtime_error("Invalid remembered pairing code");
    DATA_BLOB plain{static_cast<DWORD>(code.size()),reinterpret_cast<BYTE*>(const_cast<char*>(code.data()))}; Blob encrypted;
    if(!CryptProtectData(&plain,L"LanBridge pairing",nullptr,nullptr,nullptr,CRYPTPROTECT_UI_FORBIDDEN,&encrypted.data)) throw std::runtime_error("Cannot protect pairing credentials");
    std::filesystem::create_directories(path.parent_path());
    auto temporary=path; temporary+=L".tmp";
    try {
        std::ofstream file(temporary,std::ios::binary|std::ios::trunc);
        if(!file.write(reinterpret_cast<char*>(encrypted.data.pbData),encrypted.data.cbData) || !file.flush()) throw std::runtime_error("Cannot save pairing credentials");
        file.close();
        if(!MoveFileExW(temporary.c_str(),path.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH)) throw std::runtime_error("Cannot finalize pairing credentials");
    } catch(...) { std::error_code error; std::filesystem::remove(temporary,error); throw; }
}
std::string LoadOrCreate(const std::filesystem::path& path) {
    if(!std::filesystem::exists(path)) { auto code=bridge::RandomCode(); Save(path,code); return code; }
    auto size=std::filesystem::file_size(path);
    if(!size || size>4096) throw std::runtime_error("Invalid pairing storage");
    bridge::Bytes bytes(static_cast<size_t>(size)); std::ifstream file(path,std::ios::binary);
    if(!file.read(reinterpret_cast<char*>(bytes.data()),bytes.size())) throw std::runtime_error("Cannot read pairing credentials");
    DATA_BLOB encrypted{static_cast<DWORD>(bytes.size()),bytes.data()}; Blob plain;
    if(!CryptUnprotectData(&encrypted,nullptr,nullptr,nullptr,nullptr,CRYPTPROTECT_UI_FORBIDDEN,&plain.data)) throw std::runtime_error("Cannot unlock pairing credentials for this Windows user");
    std::string code(reinterpret_cast<char*>(plain.data.pbData),plain.data.cbData);
    if(!Valid(code)) throw std::runtime_error("Invalid saved pairing code");
    return code;
}
bridge::Bytes DiscoveryReply(const bridge::Bytes& request,uint16_t port,const std::string& code) {
    if(request.size()!=20 || std::string(request.begin(),request.begin()+4)!="LBD1" || !Valid(code) || !port) return {};
    bridge::Bytes reply{'L','B','R','1'};
    reply.insert(reply.end(),request.begin()+4,request.end()); reply.push_back(static_cast<unsigned char>(port>>8)); reply.push_back(static_cast<unsigned char>(port));
    BCRYPT_ALG_HANDLE algorithm=nullptr;
    if(BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,nullptr,BCRYPT_ALG_HANDLE_HMAC_FLAG)<0) throw std::runtime_error("Discovery HMAC algorithm");
    bridge::Bytes mac(32);
    auto result=BCryptHash(algorithm,reinterpret_cast<PUCHAR>(const_cast<char*>(code.data())),static_cast<ULONG>(code.size()),reply.data(),static_cast<ULONG>(reply.size()),mac.data(),32);
    BCryptCloseAlgorithmProvider(algorithm,0);
    if(result<0) throw std::runtime_error("Discovery HMAC failed");
    reply.insert(reply.end(),mac.begin(),mac.end()); return reply;
}
Discovery::Discovery() { WSADATA data{}; if(WSAStartup(MAKEWORD(2,2),&data)) throw std::runtime_error("Discovery Winsock startup"); }
Discovery::~Discovery() { Stop(); WSACleanup(); }
void Discovery::Stop() { stopping_=true; if(thread_.joinable()) thread_.join(); }
void Discovery::Start(const std::string& code,uint16_t transferPort,uint16_t discoveryPort) {
    Stop(); stopping_=false;
    thread_=std::thread([this,code,transferPort,discoveryPort] {
        SOCKET socket=::socket(AF_INET,SOCK_DGRAM,IPPROTO_UDP); if(socket==INVALID_SOCKET) return;
        DWORD timeout=250; setsockopt(socket,SOL_SOCKET,SO_RCVTIMEO,reinterpret_cast<char*>(&timeout),sizeof(timeout));
        BOOL exclusive=TRUE; setsockopt(socket,SOL_SOCKET,SO_EXCLUSIVEADDRUSE,reinterpret_cast<char*>(&exclusive),sizeof(exclusive));
        sockaddr_in local{}; local.sin_family=AF_INET; local.sin_port=htons(discoveryPort);
        if(bind(socket,reinterpret_cast<sockaddr*>(&local),sizeof(local))==0) {
            while(!stopping_) {
                unsigned char buffer[64]{}; sockaddr_in peer{}; int length=sizeof(peer);
                int count=recvfrom(socket,reinterpret_cast<char*>(buffer),sizeof(buffer),0,reinterpret_cast<sockaddr*>(&peer),&length);
                if(count<=0) continue;
                try {
                    auto reply=DiscoveryReply(bridge::Bytes(buffer,buffer+count),transferPort,code);
                    if(!reply.empty()) sendto(socket,reinterpret_cast<const char*>(reply.data()),static_cast<int>(reply.size()),0,reinterpret_cast<sockaddr*>(&peer),length);
                } catch(...) { /* A malformed request must not stop discovery. */ }
            }
        }
        closesocket(socket);
    });
}
}
