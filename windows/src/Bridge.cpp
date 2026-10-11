#include "Bridge.h"
#include <bcrypt.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>
#include <shlobj.h>
#include <algorithm>
#include <fstream>
#include <stdexcept>
#include <chrono>
#include <cstring>

namespace bridge {
namespace {
void Check(NTSTATUS status) { if(status<0) throw std::runtime_error("Windows cryptography failed"); }
void Exact(SOCKET s, void* buffer, size_t size, bool write) {
    auto* p=static_cast<char*>(buffer);
    while(size) {
        int n=write ? send(s,p,static_cast<int>(std::min(size,size_t(1024*1024))),0)
                    : recv(s,p,static_cast<int>(std::min(size,size_t(1024*1024))),0);
        if(n<=0) throw std::runtime_error("Connection closed or timed out");
        p+=n; size-=n;
    }
}
void Put64(Bytes& b,uint64_t n) { for(int i=7;i>=0;i--) b.push_back(static_cast<unsigned char>(n>>(i*8))); }
uint64_t Get64(const Bytes& b,size_t offset) {
    if(offset+8>b.size()) throw std::runtime_error("Invalid length");
    uint64_t n=0; for(int i=0;i<8;i++) n=(n<<8)|b[offset+i]; return n;
}
Bytes Random(size_t n) { Bytes b(n); Check(BCryptGenRandom(nullptr,b.data(),static_cast<ULONG>(n),BCRYPT_USE_SYSTEM_PREFERRED_RNG)); return b; }
class Hash {
    BCRYPT_ALG_HANDLE alg_=nullptr;
    BCRYPT_HASH_HANDLE hash_=nullptr;
public:
    Hash() { Check(BCryptOpenAlgorithmProvider(&alg_,BCRYPT_SHA256_ALGORITHM,nullptr,0)); Check(BCryptCreateHash(alg_,&hash_,nullptr,0,nullptr,0,0)); }
    ~Hash() { if(hash_) BCryptDestroyHash(hash_); if(alg_) BCryptCloseAlgorithmProvider(alg_,0); }
    void Add(const Bytes& b) { Check(BCryptHashData(hash_,const_cast<PUCHAR>(b.data()),static_cast<ULONG>(b.size()),0)); }
    Bytes Finish() { Bytes b(32); Check(BCryptFinishHash(hash_,b.data(),32,0)); return b; }
};
std::wstring SafeName(const std::string& name) {
    auto w=Wide(name);
    if(w.empty() || w.size()>180 || w==L"." || w==L"..") throw std::runtime_error("Invalid filename");
    for(auto c:w) if(c<32 || std::wstring(L"<>:\"/\\|?*").find(c)!=std::wstring::npos) throw std::runtime_error("Invalid filename");
    if(w.back()==L'.' || w.back()==L' ') throw std::runtime_error("Invalid filename");
    auto stem=w.substr(0,w.find(L'.')); std::transform(stem.begin(),stem.end(),stem.begin(),::towupper);
    if(stem==L"CON" || stem==L"PRN" || stem==L"AUX" || stem==L"NUL" ||
       (stem.size()==4 && (stem.substr(0,3)==L"COM" || stem.substr(0,3)==L"LPT") && stem[3]>=L'1' && stem[3]<=L'9'))
        throw std::runtime_error("Reserved filename");
    return w;
}
struct Incoming {
    HANDLE handle=INVALID_HANDLE_VALUE;
    std::filesystem::path partial, final;
    uint64_t size=0, done=0;
    std::unique_ptr<Hash> hash;
    ~Incoming() { if(handle!=INVALID_HANDLE_VALUE) CloseHandle(handle); if(!partial.empty()) { std::error_code e; std::filesystem::remove(partial,e); } }
};
}
std::string Utf8(const std::wstring& s) {
    if(s.empty()) return {};
    int n=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,s.data(),static_cast<int>(s.size()),nullptr,0,nullptr,nullptr);
    if(!n) throw std::runtime_error("Invalid Unicode");
    std::string b(n,'\0'); WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,s.data(),static_cast<int>(s.size()),b.data(),n,nullptr,nullptr); return b;
}
std::wstring Wide(const std::string& s) {
    if(s.empty()) return {};
    int n=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,s.data(),static_cast<int>(s.size()),nullptr,0);
    if(!n) throw std::runtime_error("Invalid UTF-8");
    std::wstring b(n,L'\0'); MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,s.data(),static_cast<int>(s.size()),b.data(),n); return b;
}
std::string RandomCode() {
    auto bytes=Random(8); std::string s; for(auto b:bytes) { s+="0123456789ABCDEF"[b>>4]; s+="0123456789ABCDEF"[b&15]; } return s;
}
std::filesystem::path ReceiveDirectory() {
    PWSTR p=nullptr; if(FAILED(SHGetKnownFolderPath(FOLDERID_Downloads,0,nullptr,&p))) throw std::runtime_error("Cannot find Downloads");
    std::filesystem::path path(p); CoTaskMemFree(p); path/=L"LanBridge"; std::filesystem::create_directories(path); return path;
}
std::vector<LocalAddress> LocalAddresses() {
    ULONG size=16384; Bytes buffer(size);
    auto* adapters=reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data());
    constexpr ULONG flags=GAA_FLAG_SKIP_ANYCAST|GAA_FLAG_SKIP_MULTICAST|GAA_FLAG_SKIP_DNS_SERVER|GAA_FLAG_INCLUDE_GATEWAYS;
    auto rc=GetAdaptersAddresses(AF_INET,flags,nullptr,adapters,&size);
    if(rc==ERROR_BUFFER_OVERFLOW) { buffer.resize(size); adapters=reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data()); rc=GetAdaptersAddresses(AF_INET,flags,nullptr,adapters,&size); }
    struct Candidate { LocalAddress value; int priority; };
    std::vector<Candidate> candidates;
    if(rc==NO_ERROR) for(auto* a=adapters;a;a=a->Next) if(a->OperStatus==IfOperStatusUp && a->IfType!=IF_TYPE_SOFTWARE_LOOPBACK) {
        MIB_IF_ROW2 row{}; row.InterfaceLuid=a->Luid;
        const bool physical=GetIfEntry2(&row)==NO_ERROR && row.InterfaceAndOperStatusFlags.HardwareInterface && !row.InterfaceAndOperStatusFlags.FilterInterface;
        const bool lan=physical && (a->IfType==IF_TYPE_IEEE80211 || a->IfType==IF_TYPE_ETHERNET_CSMACD) && a->TunnelType==TUNNEL_TYPE_NONE;
        bool gateway=false;
        for(auto* g=a->FirstGatewayAddress;g;g=g->Next) if(g->Address.lpSockaddr && g->Address.lpSockaddr->sa_family==AF_INET && reinterpret_cast<sockaddr_in*>(g->Address.lpSockaddr)->sin_addr.s_addr!=0) gateway=true;
        // Driver-reported hardware flags distinguish Wintun/VMware/Hyper-V
        // adapters from LAN NICs even when a VPN advertises Ethernet type.
        const int priority=lan ? (gateway ? (a->IfType==IF_TYPE_IEEE80211 ? 0 : 1) : (a->IfType==IF_TYPE_IEEE80211 ? 2 : 3)) : 4;
        for(auto* u=a->FirstUnicastAddress;u;u=u->Next) if(u->Address.lpSockaddr && u->Address.lpSockaddr->sa_family==AF_INET) {
            auto* ipv4=reinterpret_cast<sockaddr_in*>(u->Address.lpSockaddr);
            auto host=ntohl(ipv4->sin_addr.s_addr);
            if(u->DadState!=IpDadStatePreferred || host==0 || (host>>24)==127 || (host>>16)==0xA9FE || (host>>24)>=224) continue;
            wchar_t address[64]{};
            if(InetNtopW(AF_INET,&ipv4->sin_addr,address,64)) candidates.push_back({{address,lan},priority});
        }
    }
    std::stable_sort(candidates.begin(),candidates.end(),[](const Candidate& a,const Candidate& b) { return a.priority<b.priority; });
    std::vector<LocalAddress> result;
    for(auto& c:candidates) result.push_back(std::move(c.value));
    return result;
}
Channel::Channel(SOCKET socket,const std::string& code,const Bytes& salt,bool server):socket_(socket),server_(server) {
    BCRYPT_ALG_HANDLE sha=nullptr;
    Check(BCryptOpenAlgorithmProvider(&sha,BCRYPT_SHA256_ALGORITHM,nullptr,BCRYPT_ALG_HANDLE_HMAC_FLAG));
    Bytes secret(32);
    auto status=BCryptDeriveKeyPBKDF2(sha,reinterpret_cast<PUCHAR>(const_cast<char*>(code.data())),static_cast<ULONG>(code.size()),const_cast<PUCHAR>(salt.data()),static_cast<ULONG>(salt.size()),120000,secret.data(),32,0);
    BCryptCloseAlgorithmProvider(sha,0); Check(status);
    BCRYPT_ALG_HANDLE aes=nullptr;
    Check(BCryptOpenAlgorithmProvider(&aes,BCRYPT_AES_ALGORITHM,nullptr,0)); algorithm_=aes;
    Check(BCryptSetProperty(aes,BCRYPT_CHAINING_MODE,reinterpret_cast<PUCHAR>(const_cast<wchar_t*>(BCRYPT_CHAIN_MODE_GCM)),sizeof(BCRYPT_CHAIN_MODE_GCM),0));
    ULONG objectSize=0,n=0; Check(BCryptGetProperty(aes,BCRYPT_OBJECT_LENGTH,reinterpret_cast<PUCHAR>(&objectSize),sizeof(objectSize),&n,0));
    keyObject_.resize(objectSize);
    BCRYPT_KEY_HANDLE key=nullptr;
    Check(BCryptGenerateSymmetricKey(aes,&key,keyObject_.data(),objectSize,secret.data(),32,0)); key_=key;
    SecureZeroMemory(secret.data(),secret.size());
}
Channel::~Channel() { Close(); closesocket(socket_); if(key_) BCryptDestroyKey(static_cast<BCRYPT_KEY_HANDLE>(key_)); if(algorithm_) BCryptCloseAlgorithmProvider(static_cast<BCRYPT_ALG_HANDLE>(algorithm_),0); }
void Channel::Close() { if(!closed_.exchange(true)) shutdown(socket_,SD_BOTH); }
void Channel::Send(unsigned char type,const Bytes& data) {
    std::lock_guard lock(writeMutex_);
    if(closed_ || data.size()>262144) throw std::runtime_error("Invalid frame or closed connection");
    Bytes plain{type}; plain.insert(plain.end(),data.begin(),data.end());
    Bytes nonce=Random(12),tag(16),cipher(plain.size()),aad; aad.push_back(server_?1:0); Put64(aad,tx_);
    BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO info; BCRYPT_INIT_AUTH_MODE_INFO(info);
    info.pbNonce=nonce.data(); info.cbNonce=12; info.pbTag=tag.data(); info.cbTag=16; info.pbAuthData=aad.data(); info.cbAuthData=static_cast<ULONG>(aad.size());
    ULONG written=0;
    Check(BCryptEncrypt(static_cast<BCRYPT_KEY_HANDLE>(key_),plain.data(),static_cast<ULONG>(plain.size()),&info,nullptr,0,cipher.data(),static_cast<ULONG>(cipher.size()),&written,0));
    uint32_t length=htonl(static_cast<uint32_t>(12+cipher.size()+16));
    Exact(socket_,&length,4,true); Exact(socket_,nonce.data(),12,true); Exact(socket_,cipher.data(),cipher.size(),true); Exact(socket_,tag.data(),16,true); ++tx_;
}
std::pair<unsigned char,Bytes> Channel::Receive() {
    uint32_t wire=0; Exact(socket_,&wire,4,false); auto n=ntohl(wire);
    if(n<29 || n>262173) throw std::runtime_error("Invalid frame length");
    Bytes packet(n),plain(n-28),aad; Exact(socket_,packet.data(),n,false);
    aad.push_back(server_?0:1); Put64(aad,rx_);
    BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO info; BCRYPT_INIT_AUTH_MODE_INFO(info);
    info.pbNonce=packet.data(); info.cbNonce=12; info.pbTag=packet.data()+n-16; info.cbTag=16; info.pbAuthData=aad.data(); info.cbAuthData=static_cast<ULONG>(aad.size());
    ULONG written=0;
    auto status=BCryptDecrypt(static_cast<BCRYPT_KEY_HANDLE>(key_),packet.data()+12,n-28,&info,nullptr,0,plain.data(),static_cast<ULONG>(plain.size()),&written,0);
    if(status<0) throw std::runtime_error("Pairing code mismatch or damaged encrypted frame");
    ++rx_; auto type=plain[0]; plain.erase(plain.begin()); return {type,std::move(plain)};
}
Server::Server(std::filesystem::path directory,Callback callback):directory_(std::move(directory)),callback_(std::move(callback)) {
    WSADATA data; if(WSAStartup(MAKEWORD(2,2),&data)) throw std::runtime_error("Winsock startup failed");
    std::filesystem::create_directories(directory_);
}
Server::~Server() { Stop(); WSACleanup(); }
void Server::Emit(Event e) { callback_(std::move(e)); }
void Server::Start(uint16_t port,const std::string& code) { Stop(); stopping_=false; thread_=std::thread(&Server::Run,this,port,code); }
void Server::Stop() {
    stopping_=true;
    auto s=listener_.exchange(INVALID_SOCKET); if(s!=INVALID_SOCKET) closesocket(s);
    s=pending_.load(); if(s!=INVALID_SOCKET) shutdown(s,SD_BOTH);
    Disconnect(); if(thread_.joinable()) thread_.join();
}
void Server::Disconnect() { auto c=Active(); if(c) c->Close(); }
std::shared_ptr<Channel> Server::Active() const { std::lock_guard lock(mutex_); return channel_; }
bool Server::IsConnected() const { return authenticated_; }
void Server::Run(uint16_t port,std::string code) {
    auto listener=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);
    if(listener==INVALID_SOCKET) { Emit({Event::Error,L"无法创建网络监听"}); return; }
    BOOL exclusive=TRUE; setsockopt(listener,SOL_SOCKET,SO_EXCLUSIVEADDRUSE,reinterpret_cast<char*>(&exclusive),sizeof(exclusive));
    sockaddr_in address{}; address.sin_family=AF_INET; address.sin_addr.s_addr=INADDR_ANY; address.sin_port=htons(port);
    if(bind(listener,reinterpret_cast<sockaddr*>(&address),sizeof(address)) || listen(listener,4)) {
        closesocket(listener); Emit({Event::Error,L"端口被占用或无法监听，请关闭重复运行的 LanBridge"}); return;
    }
    listener_=listener;
    if(stopping_) { if(listener_.exchange(INVALID_SOCKET)!=INVALID_SOCKET) closesocket(listener); return; }
    Emit({Event::Info,L"正在监听 · 等待 Android 配对"});
    while(!stopping_) {
        SOCKET s=accept(listener,nullptr,nullptr); if(s==INVALID_SOCKET) break;
        pending_=s; DWORD timeout=10000; setsockopt(s,SOL_SOCKET,SO_RCVTIMEO,reinterpret_cast<char*>(&timeout),sizeof(timeout)); setsockopt(s,SOL_SOCKET,SO_SNDTIMEO,reinterpret_cast<char*>(&timeout),sizeof(timeout));
        std::shared_ptr<Channel> c;
        try {
            Bytes greeting{'L','B','0','1'}; auto salt=Random(16); greeting.insert(greeting.end(),salt.begin(),salt.end()); Exact(s,greeting.data(),greeting.size(),true);
            c=std::make_shared<Channel>(s,code,salt,true); pending_=INVALID_SOCKET;
            { std::lock_guard lock(mutex_); channel_=c; }
            auto [type,data]=c->Receive(); if(type!=1 || data.empty() || data.size()>256) throw std::runtime_error("Invalid hello");
            auto peer=Wide(std::string(data.begin(),data.end()));
            std::string hello="Windows"; c->Send(1,Bytes(hello.begin(),hello.end()));
            timeout=45000; setsockopt(s,SOL_SOCKET,SO_RCVTIMEO,reinterpret_cast<char*>(&timeout),sizeof(timeout));
            authenticated_=true; Emit({Event::Connected,L"已连接 · "+peer}); Session(c);
        } catch(const std::exception& e) { if(!stopping_) Emit({Event::Error,Wide(e.what())}); }
        authenticated_=false;
        { std::lock_guard lock(mutex_); channel_.reset(); }
        if(c) c->Close(); else { pending_=INVALID_SOCKET; closesocket(s); }
        if(!stopping_) { Emit({Event::Disconnected,L"连接已断开 · 等待重新配对"}); for(int i=0;i<10 && !stopping_;i++) Sleep(100); }
    }
    auto s=listener_.exchange(INVALID_SOCKET); if(s!=INVALID_SOCKET) closesocket(s);
}
void Server::Session(const std::shared_ptr<Channel>& c) {
    std::unique_ptr<Incoming> incoming;
    auto lastProgress=std::chrono::steady_clock::now();
    while(!stopping_) {
        auto [type,data]=c->Receive();
        switch(type) {
        case 2: {
            if(data.empty()) throw std::runtime_error("Empty text");
            auto text=Wide(std::string(data.begin(),data.end()));
            if(text.find(L'\0')!=std::wstring::npos) throw std::runtime_error("Text contains NUL");
            Emit({Event::Text,std::move(text)}); break;
        }
        case 3: {
            if(incoming || data.size()<10) throw std::runtime_error("Unexpected file header");
            size_t n=(data[0]<<8)|data[1]; if(data.size()!=n+10) throw std::runtime_error("Invalid filename length");
            auto name=SafeName(std::string(data.begin()+2,data.begin()+2+n)); auto size=Get64(data,2+n);
            if(size>100ULL*1024*1024*1024) throw std::runtime_error("File exceeds 100 GiB limit");
            if(std::filesystem::space(directory_).available<size+1024*1024) throw std::runtime_error("Not enough disk space");
            incoming=std::make_unique<Incoming>(); incoming->size=size;
            incoming->final=directory_/name;
            incoming->partial=directory_/(L"."+Wide(RandomCode())+L".part");
            incoming->handle=CreateFileW(incoming->partial.c_str(),GENERIC_WRITE,0,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,nullptr);
            if(incoming->handle==INVALID_HANDLE_VALUE) throw std::runtime_error("Cannot create received file");
            incoming->hash=std::make_unique<Hash>(); Emit({Event::Progress,L"接收 "+name,{},0,size}); break;
        }
        case 4: {
            if(!incoming || data.empty() || data.size()>incoming->size-incoming->done) throw std::runtime_error("Invalid file chunk");
            DWORD n=0; if(!WriteFile(incoming->handle,data.data(),static_cast<DWORD>(data.size()),&n,nullptr) || n!=data.size()) throw std::runtime_error("Cannot write received file");
            incoming->hash->Add(data); incoming->done+=data.size();
            auto now=std::chrono::steady_clock::now();
            if(now-lastProgress>std::chrono::milliseconds(150)) { Emit({Event::Progress,L"接收文件",{},incoming->done,incoming->size}); lastProgress=now; } break;
        }
        case 5: {
            if(!incoming || data.size()!=32 || incoming->size!=incoming->done || incoming->hash->Finish()!=data) throw std::runtime_error("File SHA-256 verification failed");
            if(!FlushFileBuffers(incoming->handle)) throw std::runtime_error("Cannot flush received file");
            CloseHandle(incoming->handle); incoming->handle=INVALID_HANDLE_VALUE;
            auto original=incoming->final;
            for(int i=0;;i++) {
                if(MoveFileExW(incoming->partial.c_str(),incoming->final.c_str(),MOVEFILE_WRITE_THROUGH)) break;
                if(GetLastError()!=ERROR_ALREADY_EXISTS && GetLastError()!=ERROR_FILE_EXISTS) throw std::runtime_error("Cannot finalize received file");
                incoming->final=original.parent_path()/(original.stem().wstring()+L" ("+std::to_wstring(i+1)+L")"+original.extension().wstring());
                if(i>10000) throw std::runtime_error("Too many filename collisions");
            }
            incoming->partial.clear(); Emit({Event::Progress,L"接收完成",{},incoming->size,incoming->size});
            Emit({Event::File,incoming->final.filename().wstring(),incoming->final}); incoming.reset(); break;
        }
        case 6: Emit({Event::Ack,Wide(std::string(data.begin(),data.end()))}); break;
        case 7: c->Send(8); break;
        case 8: break;
        default: throw std::runtime_error("Unknown packet type");
        }
    }
}
void Server::SendText(const std::wstring& text) {
    std::lock_guard transfer(transferMutex_); auto c=Active();
    if(!c || !authenticated_) throw std::runtime_error("Android is not connected");
    auto s=Utf8(text); if(s.empty() || s.size()>262144 || s.find('\0')!=std::string::npos) throw std::runtime_error("Text must be 1–262144 UTF-8 bytes without NUL");
    try { c->Send(2,Bytes(s.begin(),s.end())); Emit({Event::Info,L"文本已发送 · 等待对端确认"}); } catch(...) { c->Close(); throw; }
}
void Server::SendFiles(const std::vector<std::filesystem::path>& files) {
    std::lock_guard transfer(transferMutex_); auto c=Active();
    if(!c || !authenticated_) throw std::runtime_error("Android is not connected");
    bool started=false;
    try { for(const auto& path:files) {
        auto name=Utf8(path.filename().wstring()); SafeName(name);
        std::ifstream stream(path,std::ios::binary); if(!stream) throw std::runtime_error("Cannot open selected file");
        auto size=std::filesystem::file_size(path); if(size>100ULL*1024*1024*1024) throw std::runtime_error("File exceeds 100 GiB");
        Bytes header{static_cast<unsigned char>(name.size()>>8),static_cast<unsigned char>(name.size())}; header.insert(header.end(),name.begin(),name.end()); Put64(header,size); started=true; c->Send(3,header);
        Hash hash; uint64_t done=0; auto last=std::chrono::steady_clock::now();
        Emit({Event::Progress,L"发送 "+path.filename().wstring(),{},0,size});
        while(done<size) {
            Bytes chunk(static_cast<size_t>(std::min(uint64_t(262144),size-done)));
            if(!stream.read(reinterpret_cast<char*>(chunk.data()),chunk.size())) throw std::runtime_error("File changed or read failed");
            hash.Add(chunk); c->Send(4,chunk); done+=chunk.size();
            auto now=std::chrono::steady_clock::now(); if(now-last>std::chrono::milliseconds(150)) { Emit({Event::Progress,L"发送文件",{},done,size}); last=now; }
        }
        c->Send(5,hash.Finish()); started=false; Emit({Event::Progress,L"发送完成 · 等待对端确认",{},size,size});
    } } catch(...) { if(started) c->Close(); throw; }
}
void Server::Acknowledge(const std::wstring& message) {
    auto c=Active(); if(!c || !authenticated_) return;
    auto s=Utf8(message); try { c->Send(6,Bytes(s.begin(),s.end())); } catch(...) { c->Close(); }
}
}
