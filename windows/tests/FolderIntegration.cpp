#include "FolderTransfer.h"
#include <bcrypt.h>
#include <ws2tcpip.h>
#include <fstream>
#include <future>
#include <iostream>
#include <condition_variable>
#include <deque>
using namespace bridge;
namespace {
void Require(bool value,const char* why) { if(!value) throw std::runtime_error(why); }
Bytes Digest(const Bytes& bytes) { BCRYPT_ALG_HANDLE alg=nullptr; BCRYPT_HASH_HANDLE hash=nullptr; Bytes out(32); Require(BCryptOpenAlgorithmProvider(&alg,BCRYPT_SHA256_ALGORITHM,nullptr,0)>=0,"hash"); Require(BCryptCreateHash(alg,&hash,nullptr,0,nullptr,0,0)>=0,"hash"); BCryptHashData(hash,const_cast<PUCHAR>(bytes.data()),static_cast<ULONG>(bytes.size()),0); BCryptFinishHash(hash,out.data(),32,0); BCryptDestroyHash(hash); BCryptCloseAlgorithmProvider(alg,0); return out; }
std::shared_ptr<Channel> Connect(uint16_t port,const std::string& code) {
    auto socket=::socket(AF_INET,SOCK_STREAM,IPPROTO_TCP); sockaddr_in address{}; address.sin_family=AF_INET; address.sin_addr.s_addr=htonl(INADDR_LOOPBACK); address.sin_port=htons(port);
    Require(connect(socket,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0,"connect"); DWORD timeout=10000; setsockopt(socket,SOL_SOCKET,SO_RCVTIMEO,reinterpret_cast<char*>(&timeout),sizeof(timeout));
    Bytes greeting(20); size_t done=0; while(done<20) { auto n=recv(socket,reinterpret_cast<char*>(greeting.data()+done),static_cast<int>(20-done),0); Require(n>0,"greeting"); done+=n; }
    return std::make_shared<Channel>(socket,code,Bytes(greeting.begin()+4,greeting.end()),false);
}
template<class Work> void Reject(Work work) { bool failed=false; try { work(); } catch(...) {failed=true;} Require(failed,"Unsafe folder accepted"); }
}
int wmain(int argc,wchar_t** argv) {
    std::filesystem::path root;
    try {
        if(argc==3 && wcscmp(argv[1],L"--crash-stage")==0) {
            auto destination=std::filesystem::path(argv[2]); folders::Receiver receiver(destination,{99,"never-published",1,0,3}); receiver.Consume(11,folders::FileHeader("partial.bin",3)); receiver.Consume(4,Bytes{1});
            std::ofstream(destination/L"child-ready")<<"ready"; Sleep(INFINITE); return 0;
        }
        root=std::filesystem::temp_directory_path()/(L"LanBridge-folder-test-"+Wide(RandomCode())); std::filesystem::create_directory(root);
        for(auto name:{"../escape","/root","a//b","a/./b","C:/file","a\\b","a/CON.txt","a/LPT¹.txt","a/b.","a/b "}) Reject([&]{folders::RelativePath(name);});
        auto destination=root/L"received"; std::filesystem::create_directory(destination);
        {
            wchar_t executable[32768]{}; GetModuleFileNameW(nullptr,executable,32768);
            auto command=L"\""+std::wstring(executable)+L"\" --crash-stage \""+destination.wstring()+L"\""; STARTUPINFOW startup{sizeof(startup)}; PROCESS_INFORMATION process{};
            Require(CreateProcessW(executable,command.data(),nullptr,nullptr,FALSE,CREATE_NO_WINDOW,nullptr,nullptr,&startup,&process)!=FALSE,"crash-stage child");
            for(int i=0;i<200 && !std::filesystem::exists(destination/L"child-ready");++i) Sleep(20);
            bool ready=std::filesystem::exists(destination/L"child-ready");
            if(ready) { folders::Recover(destination); bool exists=false; for(auto& entry:std::filesystem::directory_iterator(destination)) if(entry.path().filename().wstring().starts_with(L".lanbridge-")) exists=true; Require(exists,"live process staging removed"); }
            TerminateProcess(process.hProcess,1); WaitForSingleObject(process.hProcess,5000); CloseHandle(process.hProcess); CloseHandle(process.hThread); Require(ready,"child staging ready");
            folders::Recover(destination); for(auto& entry:std::filesystem::directory_iterator(destination)) Require(!entry.path().filename().wstring().starts_with(L".lanbridge-"),"crash staging recovery"); Require(!std::filesystem::exists(destination/L"never-published"),"crash published partial folder");
        }
        folders::Header header{42,Utf8(L"照片😀"),2,2,3}; Require(folders::DecodeHeader(folders::EncodeHeader(header)).bytes==3,"header interop");
        auto original=destination/Wide(header.name); std::filesystem::create_directory(original); std::ofstream(original/L"keep.txt")<<"keep";
        { folders::Receiver receiver(destination,header); receiver.Consume(10,Bytes{'a'}); receiver.Consume(10,Bytes{'e','m','p','t','y'}); receiver.Consume(11,folders::FileHeader("a/file.bin",3)); receiver.Consume(4,Bytes{1,2,3}); receiver.Consume(5,Digest(Bytes{1,2,3})); receiver.Consume(11,folders::FileHeader("zero.txt",0)); receiver.Consume(5,Digest({})); auto path=receiver.Commit(); Require(path.filename()==Wide(header.name)+L" (1)","root collision"); Require(std::filesystem::is_directory(path/L"empty") && std::filesystem::file_size(path/L"zero.txt")==0,"empty preservation"); }
        { folders::Receiver receiver(destination,{1,"broken",1,0,3}); receiver.Consume(11,folders::FileHeader("a.bin",3)); receiver.Consume(4,Bytes{1}); Reject([&]{receiver.Consume(5,Bytes(32));}); }
        { folders::Receiver receiver(destination,{1,"bad-path",1,0,0}); Reject([&]{receiver.Consume(11,folders::FileHeader("missing/file",0));}); Reject([&]{receiver.Commit();}); }
        { folders::Receiver receiver(destination,{1,"duplicate",0,2,0}); receiver.Consume(10,Bytes{'A'}); Reject([&]{receiver.Consume(10,Bytes{'a'});}); }
        { folders::Receiver receiver(destination,{1,"empty-root",0,0,0}); Require(std::filesystem::is_directory(receiver.Commit()),"empty root"); }
        for(auto& entry:std::filesystem::directory_iterator(destination)) Require(!entry.path().filename().wstring().starts_with(L".lanbridge-"),"staging leak");
        auto source=root/L"source"; std::filesystem::create_directories(source/L"nested"/L"empty"); std::ofstream(source/L"nested"/L"file.txt",std::ios::binary)<<"123";
        auto plan=folders::Collect(source); Require(plan.files==1 && plan.directories==2 && plan.bytes==3,"collect counts");
        bool fixture=argc>1 && wcscmp(argv[1],L"--fixture")==0;
        auto port=fixture?static_cast<uint16_t>(std::stoi(argv[2])):static_cast<uint16_t>(48000+GetCurrentProcessId()%1000); auto code=fixture?Utf8(argv[3]):RandomCode();
        std::mutex mutex; std::condition_variable cv; bool listening=false, stop=false; std::deque<std::filesystem::path> echoes;
        Server server(destination,[&](Event e){ std::lock_guard lock(mutex); if(e.kind==Event::Info) listening=true; if(fixture && e.kind==Event::Folder && !e.path.empty()) echoes.push_back(e.path); cv.notify_all(); });
        server.Start(port,code); {std::unique_lock lock(mutex); Require(cv.wait_for(lock,std::chrono::seconds(10),[&]{return listening;}),"listen");}
        if(fixture) {
            auto worker=std::async(std::launch::async,[&]{ while(true) { std::filesystem::path path; {std::unique_lock lock(mutex); cv.wait(lock,[&]{return stop || !echoes.empty();}); if(stop) break; path=echoes.front(); echoes.pop_front();} try {server.SendFiles({path});}catch(const std::exception& e){std::cerr<<"Echo failed: "<<e.what()<<std::endl;} } });
            std::cout<<"READY"<<std::endl; std::string line; std::getline(std::cin,line); {std::lock_guard lock(mutex); stop=true;} cv.notify_all(); server.Stop(); worker.get();
        } else {
            auto client=Connect(port,code); std::string hello="test\nLB-FOLDER1"; client->Send(1,Bytes(hello.begin(),hello.end())); Require(client->Receive().first==1,"hello");
            auto send=std::async(std::launch::async,[&]{server.SendFiles({source});});
            auto [type,bytes]=client->Receive(); Require(type==9,"folder begin"); auto metadata=folders::DecodeHeader(bytes); client->Send(14,folders::Reply(metadata.id,true));
            folders::Receiver receiver(root,metadata);
            while(true) { auto [t,b]=client->Receive(); if(t==12) { Require(folders::Id(b)==metadata.id,"end id"); auto published=receiver.Commit(); Require(std::filesystem::is_directory(published/L"nested"/L"empty"),"wire empty directory"); client->Send(15,folders::Reply(metadata.id,true)); break; } receiver.Consume(t,b); }
            send.get(); client->Send(7); Require(client->Receive().first==8,"connected after folder");
            auto reject=std::async(std::launch::async,[&]{Reject([&]{server.SendFiles({source});});}); auto rejected=client->Receive(); Require(rejected.first==9,"rejected begin"); client->Send(14,folders::Reply(folders::Id(rejected.second),false,"not authorized")); reject.get(); client->Send(7); Require(client->Receive().first==8,"rejection kept connection");
            client->Send(9,folders::EncodeHeader({9,"cancelled",1,0,3})); Require(client->Receive().first==14,"cancel ready"); client->Send(11,folders::FileHeader("partial.bin",3)); client->Send(4,Bytes{1}); auto cancel=folders::Reply(9,false); cancel.resize(8); client->Send(13,cancel); Require(client->Receive().first==15,"cancel reply"); Require(!std::filesystem::exists(destination/L"cancelled"),"cancel published"); client->Send(7); Require(client->Receive().first==8,"cancel kept connection");
            client->Close(); server.Stop();
        }
        Require(root.is_absolute() && std::filesystem::equivalent(root.parent_path(),std::filesystem::temp_directory_path()) && root.filename().wstring().starts_with(L"LanBridge-folder-test-"),"cleanup boundary"); std::filesystem::remove_all(root);
        std::cout<<"PASS: folder paths, collision, empty directories/files, checksum cleanup, encrypted folder transfer, rejection and cancellation preserve connection"<<std::endl; return 0;
    } catch(const std::exception& e) {std::cerr<<"FAIL: "<<e.what()<<std::endl; return 1;}
}
