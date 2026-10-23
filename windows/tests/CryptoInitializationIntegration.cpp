#include "Bridge.h"
#include <bcrypt.h>
#include <algorithm>
#include <array>
#include <iostream>
#include <stdexcept>
#include <type_traits>

namespace fault {
enum class Point { None, Open, CreateHash, Derive, OpenAes, SetMode, ObjectLength, GenerateKey };
Point point=Point::None;
bool injected=false;
bool socketOwnershipValid=true;
int opened=0,closed=0,invalidCloses=0;
std::array<BCRYPT_ALG_HANDLE,8> live{};
constexpr NTSTATUS Failure=static_cast<NTSTATUS>(0xC000009AL);
bool Fail(Point call) { if(point!=call) return false; injected=true; return true; }

NTSTATUS Open(BCRYPT_ALG_HANDLE* out,LPCWSTR algorithm,LPCWSTR implementation,ULONG flags) {
    if(Fail(Point::Open) || (wcscmp(algorithm,BCRYPT_AES_ALGORITHM)==0 && Fail(Point::OpenAes))) return Failure;
    auto status=BCryptOpenAlgorithmProvider(out,algorithm,implementation,flags);
    if(status>=0) {
        auto slot=std::find(live.begin(),live.end(),nullptr);
        if(slot==live.end()) std::terminate();
        *slot=*out; ++opened;
    }
    return status;
}
NTSTATUS Close(BCRYPT_ALG_HANDLE value,ULONG flags) {
    auto slot=std::find(live.begin(),live.end(),value);
    if(slot==live.end() || !value) { ++invalidCloses; return Failure; }
    auto status=BCryptCloseAlgorithmProvider(value,flags);
    if(status>=0) { *slot=nullptr; ++closed; }
    return status;
}
NTSTATUS CreateHash(BCRYPT_ALG_HANDLE algorithm,BCRYPT_HASH_HANDLE* out,PUCHAR object,ULONG objectSize,PUCHAR secret,ULONG secretSize,ULONG flags) {
    return Fail(Point::CreateHash)?Failure:BCryptCreateHash(algorithm,out,object,objectSize,secret,secretSize,flags);
}
NTSTATUS Derive(BCRYPT_ALG_HANDLE algorithm,PUCHAR password,ULONG passwordSize,PUCHAR salt,ULONG saltSize,ULONGLONG iterations,PUCHAR output,ULONG outputSize,ULONG flags) {
    return Fail(Point::Derive)?Failure:BCryptDeriveKeyPBKDF2(algorithm,password,passwordSize,salt,saltSize,iterations,output,outputSize,flags);
}
NTSTATUS SetProperty(BCRYPT_HANDLE value,LPCWSTR property,PUCHAR input,ULONG size,ULONG flags) {
    return Fail(Point::SetMode)?Failure:BCryptSetProperty(value,property,input,size,flags);
}
NTSTATUS GetProperty(BCRYPT_HANDLE value,LPCWSTR property,PUCHAR output,ULONG size,ULONG* written,ULONG flags) {
    return Fail(Point::ObjectLength)?Failure:BCryptGetProperty(value,property,output,size,written,flags);
}
NTSTATUS GenerateKey(BCRYPT_ALG_HANDLE algorithm,BCRYPT_KEY_HANDLE* out,PUCHAR object,ULONG objectSize,PUCHAR secret,ULONG secretSize,ULONG flags) {
    return Fail(Point::GenerateKey)?Failure:BCryptGenerateSymmetricKey(algorithm,out,object,objectSize,secret,secretSize,flags);
}
}

// Compile the actual private Hash/Digest and Channel implementations in an
// isolated test executable. Only the selected CNG calls are intercepted; no
// test hooks or fault switches are linked into the application.
#define BCryptOpenAlgorithmProvider fault::Open
#define BCryptCloseAlgorithmProvider fault::Close
#define BCryptCreateHash fault::CreateHash
#define BCryptDeriveKeyPBKDF2 fault::Derive
#define BCryptSetProperty fault::SetProperty
#define BCryptGetProperty fault::GetProperty
#define BCryptGenerateSymmetricKey fault::GenerateKey
#include "../src/Bridge.cpp"
#include "../src/FolderTransfer.cpp"
#undef BCryptOpenAlgorithmProvider
#undef BCryptCloseAlgorithmProvider
#undef BCryptCreateHash
#undef BCryptDeriveKeyPBKDF2
#undef BCryptSetProperty
#undef BCryptGetProperty
#undef BCryptGenerateSymmetricKey

void Require(bool value,const char* why) { if(!value) throw std::runtime_error(why); }

template<class Action>
void Probe(const char* name,fault::Point point,Action action,bool verbose=true) {
    fault::point=point; fault::injected=false;
    fault::socketOwnershipValid=true;
    fault::opened=0; fault::closed=0; fault::invalidCloses=0;
    bool threw=false;
    try { action(); } catch(const std::exception&) { threw=true; }
    const bool released=std::all_of(fault::live.begin(),fault::live.end(),[](auto value){return value==nullptr;});
    // Reclaim leaked handles even when testing an unfixed revision.
    for(auto& value:fault::live) if(value) { BCryptCloseAlgorithmProvider(value,0); value=nullptr; }
    if(verbose) std::cout<<name<<": opened="<<fault::opened<<", closed="<<fault::closed
                         <<", invalid_closes="<<fault::invalidCloses<<std::endl;
    Require(threw==(point!=fault::Point::None),"Unexpected initialization outcome");
    Require(fault::injected==(point!=fault::Point::None),"Failure injection point was not reached");
    Require(released && fault::opened==fault::closed,"Algorithm provider leaked during initialization");
    Require(fault::invalidCloses==0,"Algorithm provider closed more than once");
    Require(fault::socketOwnershipValid,"Failed Channel constructor consumed caller socket");
}

void Channel() {
    SOCKET socket=::socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);
    Require(socket!=INVALID_SOCKET,"Create initialization test socket");
    try { bridge::Channel channel(socket,"0123456789ABCDEF",bridge::Bytes(16),true); }
    catch(...) {
        // The caller retains socket ownership if the Channel constructor fails.
        int type=0,length=sizeof(type);
        const auto valid=getsockopt(socket,SOL_SOCKET,SO_TYPE,reinterpret_cast<char*>(&type),&length);
        closesocket(socket);
        fault::socketOwnershipValid=valid==0;
        throw;
    }
}

int main() {
    WSADATA data{};
    if(WSAStartup(MAKEWORD(2,2),&data)) return 1;
    try {
        static_assert(!std::is_copy_constructible_v<bridge::Hash>);
        static_assert(!std::is_copy_constructible_v<bridge::folders::Digest>);
        Probe("Hash normal",fault::Point::None,[]{ bridge::Hash hash; hash.Add({'a','b','c'}); Require(hash.Finish().size()==32,"SHA256 output"); });
        Probe("Digest normal",fault::Point::None,[]{ bridge::folders::Digest digest; digest.Add({'a','b','c'}); Require(digest.Finish().size()==32,"SHA256 output"); });
        Probe("Channel normal",fault::Point::None,Channel);
        Probe("Hash open failure",fault::Point::Open,[]{ bridge::Hash hash; });
        Probe("Digest open failure",fault::Point::Open,[]{ bridge::folders::Digest digest; });
        Probe("Hash creation failure",fault::Point::CreateHash,[]{ bridge::Hash hash; });
        Probe("Digest creation failure",fault::Point::CreateHash,[]{ bridge::folders::Digest digest; });
        Probe("Channel SHA open failure",fault::Point::Open,Channel);
        Probe("Channel PBKDF2 failure",fault::Point::Derive,Channel);
        Probe("Channel AES open failure",fault::Point::OpenAes,Channel);
        Probe("Channel GCM setup failure",fault::Point::SetMode,Channel);
        Probe("Channel object length failure",fault::Point::ObjectLength,Channel);
        Probe("Channel key generation failure",fault::Point::GenerateKey,Channel);
        for(int i=0;i<5;++i) {
            Probe("Repeated failure",fault::Point::GenerateKey,Channel,false);
            Probe("Repeated success",fault::Point::None,Channel,false);
        }
        std::cout<<"PASS: initialization failure cleanup, ordinary destruction, repeated cycles, socket ownership"<<std::endl;
        WSACleanup(); return 0;
    } catch(const std::exception& error) {
        std::cerr<<"FAIL: "<<error.what()<<std::endl;
        WSACleanup(); return 1;
    }
}
