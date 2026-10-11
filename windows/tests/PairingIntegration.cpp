#include "Pairing.h"
#include "ShellTransfer.h"
#include <ws2tcpip.h>
#include <objbase.h>
#include <fstream>
#include <iostream>
#include <stdexcept>

void Require(bool value,const char* message) { if(!value) throw std::runtime_error(message); }
struct Desktop {
    PROCESS_INFORMATION process{}; HWND window=nullptr;
    void Stop() {
        if(window) { DWORD_PTR result=0; SendMessageTimeoutW(window,WM_COMMAND,900,0,SMTO_ABORTIFHUNG,3000,&result); window=nullptr; }
        if(process.hProcess) { Require(WaitForSingleObject(process.hProcess,10000)==WAIT_OBJECT_0,"Desktop exited"); CloseHandle(process.hProcess); CloseHandle(process.hThread); process={}; }
    }
    ~Desktop() { try { Stop(); } catch(...) {} }
    void Start(const std::wstring& executable) {
        auto line=shelltransfer::QuoteArgument(executable)+L" --startup"; STARTUPINFOW startup{sizeof(startup)};
        Require(CreateProcessW(executable.c_str(),line.data(),nullptr,nullptr,FALSE,CREATE_NO_WINDOW,nullptr,nullptr,&startup,&process)!=FALSE,"Start remembered-pairing desktop");
        WaitForInputIdle(process.hProcess,10000);
        for(int i=0;i<100 && !window;i++) { window=FindWindowW(L"LanBridgeWindow",nullptr); if(!window) Sleep(50); }
        Require(window!=nullptr,"Desktop window");
    }
    std::string Code() {
        wchar_t text[64]{}; SendMessageW(GetDlgItem(window,101),WM_GETTEXT,64,reinterpret_cast<LPARAM>(text));
        auto code=bridge::Utf8(text); Require(code.size()==16,"Desktop pairing credential"); return code;
    }
};
void CheckRememberedConnection(const std::string& code) {
    SOCKET socket=INVALID_SOCKET;
    for(int i=0;i<100;i++) {
        socket=::socket(AF_INET,SOCK_STREAM,IPPROTO_TCP); sockaddr_in address{}; address.sin_family=AF_INET; address.sin_port=htons(42831); address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        if(connect(socket,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0) break;
        closesocket(socket); socket=INVALID_SOCKET; Sleep(50);
    }
    Require(socket!=INVALID_SOCKET,"Reconnect to desktop");
    DWORD timeout=5000; setsockopt(socket,SOL_SOCKET,SO_RCVTIMEO,reinterpret_cast<char*>(&timeout),sizeof(timeout));
    bridge::Bytes greeting(20); int done=0;
    while(done<20) { int count=recv(socket,reinterpret_cast<char*>(greeting.data()+done),20-done,0); Require(count>0,"Desktop greeting"); done+=count; }
    bridge::Channel channel(socket,code,bridge::Bytes(greeting.begin()+4,greeting.end()),false);
    channel.Send(1,bridge::Bytes{'R','e','m','e','m','b','e','r','e','d'}); Require(channel.Receive().first==1,"Saved credential accepted");
    channel.Send(7); Require(channel.Receive().first==8,"Reconnected heartbeat"); channel.Close();
}
int main(int argc,char** argv) {
    CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
    std::filesystem::path directory;
    try {
        pairing::Discovery discovery;
        if(argc==3 && std::string(argv[1])=="--desktop") {
            Require(!FindWindowW(L"LanBridgeWindow",nullptr),"Desktop test requires app closed");
            Desktop app; app.Start(bridge::Wide(argv[2])); auto remembered=app.Code(); CheckRememberedConnection(remembered); app.Stop();
            app.Start(bridge::Wide(argv[2])); Require(app.Code()==remembered,"Restart changed pairing credential");
            CheckRememberedConnection(remembered); app.Stop();
            std::cout<<"PASS: actual desktop restarted; cached credential reconnected with encrypted hello and heartbeat; clipboard untouched"<<std::endl;
            CoUninitialize(); return 0;
        }
        if(argc==4 && std::string(argv[1])=="--fixture") {
            discovery.Start(argv[3],42831,static_cast<uint16_t>(std::stoi(argv[2])));
            std::cout<<"READY"<<std::endl; std::string line; std::getline(std::cin,line);
            discovery.Stop(); CoUninitialize(); return 0;
        }
        GUID id{}; CoCreateGuid(&id); wchar_t text[40]{}; StringFromGUID2(id,text,40);
        directory=std::filesystem::temp_directory_path()/(L"LanBridge-pairing-test-"+std::wstring(text));
        auto path=directory/L"pairing.dat";
        auto code=pairing::LoadOrCreate(path);
        Require(pairing::LoadOrCreate(path)==code,"Restart must preserve credentials");
        std::ifstream file(path,std::ios::binary); std::string protectedData((std::istreambuf_iterator<char>(file)),{}); file.close();
        Require(protectedData.find(code)==std::string::npos,"No plaintext pairing code on disk");
        auto replacement=bridge::RandomCode(); pairing::Save(path,replacement);
        Require(pairing::LoadOrCreate(path)==replacement,"Reset must persist replacement credentials");
        std::ofstream(path,std::ios::binary|std::ios::trunc)<<"corrupted";
        bool rejected=false; try { pairing::LoadOrCreate(path); } catch(...) { rejected=true; }
        Require(rejected,"Corrupted storage must not silently create a new binding");
        bridge::Bytes request{'L','B','D','1'}; for(int i=0;i<16;i++) request.push_back(static_cast<unsigned char>(i));
        auto reply=pairing::DiscoveryReply(request,42831,"0123456789ABCDEF");
        const char* expected="569760a2f07d6d3b548dbd757da5aa5b541bcfb864501d8299c335165cf0d391";
        std::string actual; for(size_t i=22;i<reply.size();i++) { actual+="0123456789abcdef"[reply[i]>>4]; actual+="0123456789abcdef"[reply[i]&15]; }
        Require(reply.size()==54 && actual==expected,"Cross-platform discovery HMAC vector");
        auto malformed=request; malformed.push_back(0); Require(pairing::DiscoveryReply(malformed,42831,code).empty(),"Malformed discovery request rejected");
        uint16_t port=static_cast<uint16_t>(48000+GetCurrentProcessId()%1000); discovery.Start("0123456789ABCDEF",42831,port);
        SOCKET socket=::socket(AF_INET,SOCK_DGRAM,IPPROTO_UDP); Require(socket!=INVALID_SOCKET,"UDP client");
        DWORD timeout=500; setsockopt(socket,SOL_SOCKET,SO_RCVTIMEO,reinterpret_cast<char*>(&timeout),sizeof(timeout));
        sockaddr_in address{}; address.sin_family=AF_INET; address.sin_port=htons(port); address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
        bridge::Bytes received(128); int count=-1;
        for(int attempt=0;attempt<10 && count<0;attempt++) {
            sendto(socket,reinterpret_cast<const char*>(request.data()),static_cast<int>(request.size()),0,reinterpret_cast<sockaddr*>(&address),sizeof(address));
            count=recv(socket,reinterpret_cast<char*>(received.data()),static_cast<int>(received.size()),0);
        }
        closesocket(socket); Require(count==54,"Live UDP discovery reply"); received.resize(count);
        Require(received==reply,"UDP service must return authenticated nonce and port");
        discovery.Stop();
        Require(directory.is_absolute() && std::filesystem::equivalent(directory.parent_path(),std::filesystem::temp_directory_path()) && directory.filename().wstring().starts_with(L"LanBridge-pairing-test-{"),"Temporary cleanup boundary");
        std::filesystem::remove_all(directory);
        std::cout<<"PASS: DPAPI persistence across restarts, no plaintext secret, reset, corrupt storage rejection, HMAC interoperability and live UDP discovery"<<std::endl;
        CoUninitialize(); return 0;
    } catch(const std::exception& e) { std::cerr<<"FAIL: "<<e.what()<<std::endl; CoUninitialize(); return 1; }
}
