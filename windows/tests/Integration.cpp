#include "Bridge.h"
#include <bcrypt.h>
#include <objbase.h>
#include <ws2tcpip.h>
#include <condition_variable>
#include <fstream>
#include <future>
#include <iostream>
#include <chrono>
#include <stdexcept>

using namespace bridge;
void Require(bool value,const char* why) { if(!value) throw std::runtime_error(why); }
Bytes Hash(const Bytes& data) {
    BCRYPT_ALG_HANDLE alg=nullptr; BCRYPT_HASH_HANDLE hash=nullptr; Bytes output(32);
    Require(BCryptOpenAlgorithmProvider(&alg,BCRYPT_SHA256_ALGORITHM,nullptr,0)>=0,"SHA open");
    Require(BCryptCreateHash(alg,&hash,nullptr,0,nullptr,0,0)>=0,"SHA create");
    BCryptHashData(hash,const_cast<PUCHAR>(data.data()),static_cast<ULONG>(data.size()),0); BCryptFinishHash(hash,output.data(),32,0);
    BCryptDestroyHash(hash); BCryptCloseAlgorithmProvider(alg,0); return output;
}
std::shared_ptr<Channel> Connect(uint16_t port,const std::string& code) {
    SOCKET socket=::socket(AF_INET,SOCK_STREAM,IPPROTO_TCP); Require(socket!=INVALID_SOCKET,"client socket");
    sockaddr_in address{}; address.sin_family=AF_INET; address.sin_port=htons(port); address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    if(connect(socket,reinterpret_cast<sockaddr*>(&address),sizeof(address))) { closesocket(socket); throw std::runtime_error("connect"); }
    DWORD timeout=10000; setsockopt(socket,SOL_SOCKET,SO_RCVTIMEO,reinterpret_cast<char*>(&timeout),sizeof(timeout));
    Bytes greeting(20); int received=0; while(received<20) { int n=recv(socket,reinterpret_cast<char*>(greeting.data())+received,20-received,0); Require(n>0,"greeting"); received+=n; }
    Require(std::string(greeting.begin(),greeting.begin()+4)=="LB01","magic");
    return std::make_shared<Channel>(socket,code,Bytes(greeting.begin()+4,greeting.end()),false);
}
Bytes Header(const std::string& name,uint64_t length) {
    Bytes result{static_cast<unsigned char>(name.size()>>8),static_cast<unsigned char>(name.size())}; result.insert(result.end(),name.begin(),name.end());
    for(int i=7;i>=0;i--) result.push_back(static_cast<unsigned char>(length>>(i*8))); return result;
}
void SendFile(Channel& c,const std::string& name,const Bytes& bytes,bool valid=true) {
    c.Send(3,Header(name,bytes.size()));
    for(size_t i=0;i<bytes.size();i+=262144) c.Send(4,Bytes(bytes.begin()+i,bytes.begin()+std::min(i+262144,bytes.size())));
    auto hash=Hash(bytes); if(!valid) hash[0]^=1; c.Send(5,hash);
}
int main(int argc,char** argv) {
    CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
    try {
        if(argc==3 && std::string(argv[1])=="--addresses") {
            auto addresses=LocalAddresses();
            for(const auto& a:addresses) std::cout<<Utf8(a.address)<<(a.autoSelect?" LAN":" manual only")<<std::endl;
            Require(!addresses.empty() && addresses.front().autoSelect && Utf8(addresses.front().address)==argv[2],"expected physical LAN address is not the default");
            std::cout<<"PASS: default physical LAN address with VPN/virtual adapters active"<<std::endl;
            CoUninitialize(); return 0;
        }
        auto directory=std::filesystem::temp_directory_path()/(L"LanBridgeTests-"+std::to_wstring(GetCurrentProcessId()));
        std::filesystem::create_directories(directory);
        std::mutex mutex; std::condition_variable cv; std::vector<Event> events;
        std::unique_ptr<Server> server;
        bool fixture=argc>1 && std::string(argv[1])=="--fixture";
        std::string code=fixture?argv[3]:RandomCode();
        uint16_t port=fixture?static_cast<uint16_t>(std::stoi(argv[2])):static_cast<uint16_t>(46000+GetCurrentProcessId()%1000);
        server=std::make_unique<Server>(directory,[&](Event e) {
            if(fixture) {
                if(e.kind==Event::Text) server->SendText(e.message);
                if(e.kind==Event::File) server->SendFiles({e.path});
                if(e.kind==Event::File || e.kind==Event::Text) server->Acknowledge(L"fixture received");
            }
            { std::lock_guard lock(mutex); events.push_back(std::move(e)); } cv.notify_all();
        });
        auto wait=[&](auto predicate) {
            std::unique_lock lock(mutex); Require(cv.wait_for(lock,std::chrono::seconds(12),[&] { return predicate(events); }),"event timeout");
        };
        server->Start(port,code);
        wait([](const auto& e) { for(auto& x:e) if(x.kind==Event::Info) return true; return false; });
        if(fixture) { std::cout<<"READY "<<directory.string()<<std::endl; std::string line; std::getline(std::cin,line); server->Stop(); std::filesystem::remove_all(directory); CoUninitialize(); return 0; }
        auto client=Connect(port,code); client->Send(1,Bytes{'t','e','s','t'}); Require(client->Receive().first==1,"hello");
        wait([](const auto& e) { for(auto& x:e) if(x.kind==Event::Connected) return true; return false; });
        auto text=Utf8(L"中文 Android ↔ Windows 😀\n多行文本"); client->Send(2,Bytes(text.begin(),text.end()));
        wait([&](const auto& e) { for(auto& x:e) if(x.kind==Event::Text && Utf8(x.message)==text) return true; return false; });
        server->SendText(L"Windows 回传文本"); auto packet=client->Receive(); Require(packet.first==2 && Wide(std::string(packet.second.begin(),packet.second.end()))==L"Windows 回传文本","reverse text");
        Bytes content(1024*1024+123); for(size_t i=0;i<content.size();i++) content[i]=static_cast<unsigned char>(i*37);
        auto name=Utf8(L"跨平台 测试.bin"); SendFile(*client,name,content);
        wait([](const auto& e) { for(auto& x:e) if(x.kind==Event::File) return true; return false; });
        auto file=directory/Wide(name); std::ifstream stream(file,std::ios::binary); Bytes actual((std::istreambuf_iterator<char>(stream)),{}); stream.close(); Require(actual==content,"file bytes");
        auto sendReverse=std::async(std::launch::async,[&] { server->SendFiles({file}); }); Require(client->Receive().first==3,"reverse begin"); Bytes reverse;
        while(true) { auto [t,b]=client->Receive(); if(t==4) reverse.insert(reverse.end(),b.begin(),b.end()); else { Require(t==5 && b==Hash(reverse),"reverse hash"); break; } }
        sendReverse.get(); Require(reverse==content,"reverse content");
        bool localFailure=false;
        try { server->SendFiles({directory/L"missing-queued-file.bin"}); } catch(...) { localFailure=true; }
        Require(localFailure,"Missing queued file accepted");
        server->SendText(L"读取失败后仍连接"); packet=client->Receive();
        Require(packet.first==2 && Wide(std::string(packet.second.begin(),packet.second.end()))==L"读取失败后仍连接","Local file failure disconnected peer");
        SendFile(*client,name,{}); wait([](const auto& e) { int n=0; for(auto& x:e) if(x.kind==Event::File) n++; return n==2; });
        Require(std::filesystem::exists(directory/L"跨平台 测试 (1).bin"),"collision/empty file");
        client->Send(7); Require(client->Receive().first==8,"keepalive");
        SendFile(*client,"corrupt.bin",Bytes{1,2,3},false);
        wait([](const auto& e) { for(auto& x:e) if(x.kind==Event::Error && x.message.find(L"SHA-256")!=std::wstring::npos) return true; return false; });
        client->Close(); client.reset(); Sleep(1200);
        Require(!std::filesystem::exists(directory/L"corrupt.bin"),"corrupt published");
        auto bad=Connect(port,RandomCode()); bad->Send(1,Bytes{'b','a','d'}); bool rejected=false; try { bad->Receive(); } catch(...) { rejected=true; } Require(rejected,"wrong code accepted"); bad.reset(); Sleep(1200);
        auto traversal=Connect(port,code); traversal->Send(1,Bytes{'t'}); traversal->Receive(); traversal->Send(3,Header("../escape.bin",0)); rejected=false; try { traversal->Receive(); } catch(...) { rejected=true; } Require(rejected,"traversal accepted"); traversal.reset(); Sleep(1200);
        server->Stop(); for(auto& entry:std::filesystem::directory_iterator(directory)) Require(entry.path().extension()!=L".part","partial leak");
        std::filesystem::remove_all(directory);
        Require(Wide(Utf8(L"中文 😀"))==L"中文 😀","UTF8");
        std::cout<<"PASS: encrypted handshake, Unicode text both ways, streamed file both ways, SHA256, empty file, collision, heartbeat, bad code, traversal, partial cleanup"<<std::endl;
        CoUninitialize(); return 0;
    } catch(const std::exception& e) { std::cerr<<"FAIL: "<<e.what()<<std::endl; CoUninitialize(); return 1; }
}
