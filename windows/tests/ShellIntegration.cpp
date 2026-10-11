#include "Bridge.h"
#include "ShellTransfer.h"
#include <shobjidl.h>
#include <shlobj.h>
#include <shellapi.h>
#include <ws2tcpip.h>
#include <bcrypt.h>
#include <appmodel.h>
#include <iostream>
#include <fstream>
#include <stdexcept>

using shelltransfer::Paths;
constexpr CLSID CommandId{0x06f3b110,0x144b,0x4cd2,{0x89,0xdb,0xbf,0xae,0x84,0x1e,0x71,0x79}};
void Require(bool condition, const char* message) { if(!condition) throw std::runtime_error(message); }
template<class Work> void Reject(Work work) { bool rejected=false; try { work(); } catch(...) { rejected=true; } Require(rejected,"Malformed request accepted"); }
struct Temporary {
    std::filesystem::path path;
    Temporary() {
        GUID id{}; CoCreateGuid(&id); wchar_t text[40]{}; StringFromGUID2(id,text,40);
        path=std::filesystem::temp_directory_path()/(L"LanBridge-shell-test-"+std::wstring(text)); std::filesystem::create_directory(path);
    }
    ~Temporary() {
        // Only remove this test's absolute, uniquely named temporary directory.
        std::error_code error;
        auto root=std::filesystem::temp_directory_path(error).lexically_normal();
        if(!error && path.is_absolute() && path.parent_path().lexically_normal()==root && path.filename().wstring().starts_with(L"LanBridge-shell-test-{")) std::filesystem::remove_all(path,error);
    }
};
struct Process {
    PROCESS_INFORMATION info{}; HWND window=nullptr;
    ~Process() {
        if(window) SendMessageTimeoutW(window,WM_COMMAND,900,0,SMTO_ABORTIFHUNG,3000,nullptr);
        if(info.hProcess) { WaitForSingleObject(info.hProcess,10000); CloseHandle(info.hProcess); CloseHandle(info.hThread); }
    }
};
IShellItemArray* Selection(const Paths& paths) {
    std::vector<PIDLIST_ABSOLUTE> ids;
    for(const auto& path:paths) {
        PIDLIST_ABSOLUTE id=nullptr; Require(SUCCEEDED(SHParseDisplayName(path.c_str(),nullptr,&id,0,nullptr)),"Parse shell item"); ids.push_back(id);
    }
    IShellItemArray* items=nullptr; auto result=SHCreateShellItemArrayFromIDLists(static_cast<UINT>(ids.size()),const_cast<PCIDLIST_ABSOLUTE*>(ids.data()),&items);
    for(auto id:ids) CoTaskMemFree(id); Require(SUCCEEDED(result),"Create shell selection"); return items;
}
void CheckCommand(IExplorerCommand* command,const Paths& paths,const std::filesystem::path& folder) {
    auto* items=Selection(paths); PWSTR title=nullptr,icon=nullptr;
    Require(SUCCEEDED(command->GetTitle(items,&title)) && std::wstring(title)==L"发送到手机","Menu title"); CoTaskMemFree(title);
    Require(SUCCEEDED(command->GetIcon(items,&icon)) && std::wstring(icon).ends_with(L",-101"),"Menu icon"); CoTaskMemFree(icon);
    EXPCMDSTATE state; Require(SUCCEEDED(command->GetState(items,FALSE,&state)) && state==ECS_ENABLED,"Multi-file state"); items->Release();
    items=Selection(Paths{paths.front(),folder});
    Require(SUCCEEDED(command->GetState(items,FALSE,&state)) && state==ECS_HIDDEN,"Mixed folder selection hidden"); items->Release();
    auto archive=folder/L"中文 archive.zip";
    items=Selection(Paths{archive});
    SFGAOF attributes=0;
    Require(SUCCEEDED(items->GetAttributes(SIATTRIBFLAGS_AND,SFGAO_FOLDER,&attributes)),"ZIP shell attributes");
    std::cout<<"ZIP has SFGAO_FOLDER: "<<((attributes & SFGAO_FOLDER)!=0)<<std::endl;
    Require(SUCCEEDED(command->GetState(items,FALSE,&state)) && state==ECS_ENABLED,"ZIP file state"); items->Release();
    items=Selection(Paths{paths.front(),archive});
    Require(SUCCEEDED(command->GetState(items,FALSE,&state)) && state==ECS_ENABLED,"ZIP and ordinary file state"); items->Release();
    items=Selection(Paths{archive,folder/L"folder.zip"});
    Require(SUCCEEDED(command->GetState(items,FALSE,&state)) && state==ECS_HIDDEN,"ZIP with real directory hidden"); items->Release();
    GUID id{}; Require(SUCCEEDED(command->GetCanonicalName(&id)) && id==CommandId,"Canonical command ID");
}
IExplorerCommand* RegisteredCommand() {
    IExplorerCommand* command=nullptr;
    auto result=CoCreateInstance(CommandId,nullptr,CLSCTX_LOCAL_SERVER,IID_PPV_ARGS(&command));
    if(FAILED(result)) {
        std::cerr << "COM activation HRESULT: 0x" << std::hex << static_cast<unsigned long>(result) << std::dec << std::endl;
        throw std::runtime_error("Registered packaged COM activation");
    }
    return command;
}
void StartApp(Process& app,const wchar_t* executable,const std::wstring& arguments={}) {
    auto launch=shelltransfer::QuoteArgument(executable)+arguments; STARTUPINFOW startup{sizeof(startup)};
    startup.dwFlags=STARTF_USESHOWWINDOW; startup.wShowWindow=SW_HIDE;
    Require(CreateProcessW(executable,launch.data(),nullptr,nullptr,FALSE,0,nullptr,nullptr,&startup,&app.info)!=FALSE,"Start primary process");
    WaitForInputIdle(app.info.hProcess,10000);
    for(int i=0;i<100 && !app.window;i++) { app.window=FindWindowW(L"LanBridgeWindow",nullptr); if(!app.window) Sleep(50); }
    Require(app.window!=nullptr,"Primary window");
}
void Alive(Process& app) {
    Require(WaitForSingleObject(app.info.hProcess,0)==WAIT_TIMEOUT,"Main process killed by registered menu activation");
    DWORD owner=0; GetWindowThreadProcessId(app.window,&owner);
    Require(owner==app.info.dwProcessId,"Primary window replaced");
}
void Heartbeat(bridge::Channel& channel) {
    channel.Send(7);
    while(true) {
        auto [type,data]=channel.Receive();
        if(type==7) { channel.Send(8); continue; }
        Require(type==8,"Heartbeat reply after menu activation"); return;
    }
}
void EnableMenuAgain(const wchar_t* script) {
    Temporary temporary; auto log=temporary.path/L"menu-enable.log";
    wchar_t system[32768]{}; Require(GetSystemDirectoryW(system,32768)>0,"System PowerShell path");
    auto powershell=std::filesystem::path(system)/L"WindowsPowerShell"/L"v1.0"/L"powershell.exe";
    auto arguments=shelltransfer::QuoteArgument(powershell.wstring())+
        L" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "+shelltransfer::QuoteArgument(script)+L" -Action Enable -LogPath "+shelltransfer::QuoteArgument(log.wstring());
    STARTUPINFOW startup{sizeof(startup)}; Process child;
    Require(CreateProcessW(powershell.c_str(),arguments.data(),nullptr,nullptr,FALSE,CREATE_NO_WINDOW,nullptr,nullptr,&startup,&child.info)!=FALSE,"Repeat menu enable");
    Require(WaitForSingleObject(child.info.hProcess,30000)==WAIT_OBJECT_0,"Menu enable completed");
    DWORD code=1;
    if(!GetExitCodeProcess(child.info.hProcess,&code) || code!=0) {
        std::ifstream details(log,std::ios::binary);
        std::cerr<<"Menu enable exit "<<code<<": "<<std::string(std::istreambuf_iterator<char>(details),{})<<std::endl;
        Require(false,"Menu enable succeeded");
    }
}
std::unique_ptr<bridge::Channel> Pair(HWND window) {
    wchar_t text[64]{}; SendMessageW(GetDlgItem(window,101),WM_GETTEXT,64,reinterpret_cast<LPARAM>(text));
    auto code=bridge::Utf8(text); Require(code.size()==16,"Pairing code");
    SOCKET socket=INVALID_SOCKET;
    for(int i=0;i<100;i++) {
        socket=::socket(AF_INET,SOCK_STREAM,IPPROTO_TCP); sockaddr_in address{}; address.sin_family=AF_INET; address.sin_addr.s_addr=htonl(INADDR_LOOPBACK); address.sin_port=htons(42831);
        if(connect(socket,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0) break;
        closesocket(socket); socket=INVALID_SOCKET; Sleep(50);
    }
    Require(socket!=INVALID_SOCKET,"Connect test peer"); DWORD timeout=10000; setsockopt(socket,SOL_SOCKET,SO_RCVTIMEO,reinterpret_cast<char*>(&timeout),sizeof(timeout));
    bridge::Bytes hello(20); int done=0; while(done<20) { auto n=recv(socket,reinterpret_cast<char*>(hello.data()+done),20-done,0); Require(n>0,"Server greeting"); done+=n; }
    auto channel=std::make_unique<bridge::Channel>(socket,code,bridge::Bytes(hello.begin()+4,hello.end()),false);
    channel->Send(1,bridge::Bytes{'S','h','e','l','l',' ','t','e','s','t'}); Require(channel->Receive().first==1,"Handshake"); return channel;
}
std::pair<std::wstring,bridge::Bytes> ReceiveFile(bridge::Channel& channel) {
    bridge::Bytes bytes; std::wstring name; uint64_t size=0;
    while(true) {
        auto [type,data]=channel.Receive();
        if(type==7) { channel.Send(8); continue; }
        if(type==3) {
            Require(name.empty() && data.size()>=10,"File header"); size_t n=(data[0]<<8)|data[1]; Require(data.size()==n+10,"Header size");
            name=bridge::Wide(std::string(data.begin()+2,data.begin()+2+n));
            for(size_t i=2+n;i<data.size();i++) size=(size<<8)|data[i];
        } else if(type==4) { Require(!name.empty(),"File chunk order"); bytes.insert(bytes.end(),data.begin(),data.end()); }
        else if(type==5) {
            Require(bytes.size()==size && data.size()==32,"File length"); bridge::Bytes hash(32);
            BCRYPT_ALG_HANDLE algorithm=nullptr; Require(BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,nullptr,0)==0,"SHA algorithm");
            auto result=BCryptHash(algorithm,nullptr,0,bytes.data(),static_cast<ULONG>(bytes.size()),hash.data(),32); BCryptCloseAlgorithmProvider(algorithm,0);
            Require(result==0 && hash==data,"File SHA-256"); return {name,bytes};
        } else Require(false,"Unexpected frame");
    }
}
int wmain(int argc,wchar_t** argv) {
    CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED); WSADATA winsock{}; WSAStartup(MAKEWORD(2,2),&winsock);
    try {
        Temporary temporary; auto a=temporary.path/L"中文 file & one.bin", b=temporary.path/L"second.bin";
        std::ofstream(a,std::ios::binary)<<"first payload"; std::ofstream(b,std::ios::binary)<<"second payload";
        // A valid empty ZIP (end-of-central-directory record), not a renamed text file.
        const char archiveBytes[22]={'P','K',5,6};
        std::ofstream(temporary.path/L"中文 archive.zip",std::ios::binary).write(archiveBytes,sizeof(archiveBytes));
        std::filesystem::create_directory(temporary.path/L"folder.zip");
        Paths paths{a,b}; auto bytes=shelltransfer::Encode(paths);
        Require(shelltransfer::Decode(bytes.data(),bytes.size())==paths,"Unicode multi-file IPC");
        Reject([&] { shelltransfer::Decode(bytes.data(),bytes.size()-1); });
        auto corrupt=bytes; corrupt.push_back(0); Reject([&] { shelltransfer::Decode(corrupt.data(),corrupt.size()); });
        Reject([&] { shelltransfer::Encode(Paths{L"relative.txt"}); });
        auto request=shelltransfer::WriteRequest(paths); Require(shelltransfer::ReadRequest(request)==paths && !std::filesystem::exists(request),"Request consumed once");
        Reject([&] { shelltransfer::ReadRequest(a); }); Require(std::filesystem::exists(a),"Unrelated file preserved");
        for(auto text:{std::wstring(L"C:\\中文 & name\\"),std::wstring(L"C:\\a\\\"b\\"),std::wstring(L"")}) {
            int n=0; auto command=L"app "+shelltransfer::QuoteArgument(text); auto parsed=CommandLineToArgvW(command.c_str(),&n);
            Require(parsed && n==2 && parsed[1]==text,"Windows command quoting"); LocalFree(parsed);
        }
        IExplorerCommand* command=nullptr; HMODULE library=nullptr;
        bool live=(argc==3 || argc==4) && wcscmp(argv[1],L"--registered-live")==0;
        bool registered=live || (argc==2 && wcscmp(argv[1],L"--registered")==0);
        bool selectionOnly=argc==3 && wcscmp(argv[1],L"--selection-only")==0;
        if(live) {
            Require(!FindWindowW(L"LanBridgeWindow",nullptr),"Close running app before isolated registered test");
            Process app; StartApp(app,argv[2]);
            UINT32 length=0;
            Require(GetPackageFullName(app.info.hProcess,&length,nullptr)==APPMODEL_ERROR_NO_PACKAGE,"Transfer process must remain unpackaged");
            auto channel=Pair(app.window);
            if(argc==4) {
                for(int i=0;i<2;i++) { EnableMenuAgain(argv[3]); Alive(app); Heartbeat(*channel); }
                std::cout<<"PASS: repeated menu Enable preserved original process and paired connection"<<std::endl;
            }
            for(int i=0;i<30;i++) {
                command=RegisteredCommand(); CheckCommand(command,paths,temporary.path); command->Release(); command=nullptr;
                Sleep(200); Alive(app); Heartbeat(*channel);
            }
            command=RegisteredCommand(); auto* items=Selection(paths);
            Require(SUCCEEDED(command->Invoke(items,nullptr)),"Registered connected multi-file Invoke"); items->Release();
            auto first=ReceiveFile(*channel),second=ReceiveFile(*channel);
            Require(first.first==a.filename() && std::string(first.second.begin(),first.second.end())=="first payload","Registered first file bytes");
            Require(second.first==b.filename() && std::string(second.second.begin(),second.second.end())=="second payload","Registered second file bytes");
            command->Release(); command=nullptr; Sleep(1000); Alive(app); Heartbeat(*channel); channel->Close();
            std::cout<<"PASS: 30 installed COM menu activations while paired; original process and encrypted connection survived; registered multi-file Invoke verified by SHA-256; clipboard untouched"<<std::endl;
            WSACleanup(); CoUninitialize(); return 0;
        }
        if(registered) command=RegisteredCommand();
        else {
            Require(argc==3,"Expected app and extension paths"); library=LoadLibraryW(argv[2]); Require(library!=nullptr,"Load extension");
            auto factoryProc=reinterpret_cast<HRESULT(__stdcall*)(REFCLSID,REFIID,void**)>(GetProcAddress(library,"DllGetClassObject")); Require(factoryProc!=nullptr,"DLL export");
            IClassFactory* factory=nullptr; Require(SUCCEEDED(factoryProc(CommandId,IID_PPV_ARGS(&factory))),"Class factory");
            Require(SUCCEEDED(factory->CreateInstance(nullptr,IID_PPV_ARGS(&command))),"Explorer command"); factory->Release();
        }
        CheckCommand(command,paths,temporary.path);
        if(!registered && !selectionOnly) {
            Require(!FindWindowW(L"LanBridgeWindow",nullptr),"Close running app before isolated shell test");
            Process app; request=shelltransfer::WriteRequest(Paths{a});
            auto launch=shelltransfer::QuoteArgument(argv[1])+L" --send-request "+shelltransfer::QuoteArgument(request.wstring()); STARTUPINFOW startup{sizeof(startup)};
            startup.dwFlags=STARTF_USESHOWWINDOW; startup.wShowWindow=SW_HIDE;
            Require(CreateProcessW(argv[1],launch.data(),nullptr,nullptr,FALSE,0,nullptr,nullptr,&startup,&app.info)!=FALSE,"Cold share launch");
            WaitForInputIdle(app.info.hProcess,10000);
            for(int i=0;i<100 && !app.window;i++) { app.window=FindWindowW(L"LanBridgeWindow",nullptr); if(!app.window) Sleep(50); }
            Require(app.window!=nullptr,"Primary window"); Sleep(100);
            auto* items=Selection(Paths{b}); Require(SUCCEEDED(command->Invoke(items,nullptr)),"Warm shell Invoke"); items->Release();
            Sleep(300); auto channel=Pair(app.window);
            auto first=ReceiveFile(*channel),second=ReceiveFile(*channel);
            Require(first.first==a.filename() && std::string(first.second.begin(),first.second.end())=="first payload","Cold queue transmitted");
            Require(second.first==b.filename() && std::string(second.second.begin(),second.second.end())=="second payload","Warm queued share transmitted in order");
            items=Selection(paths); Require(SUCCEEDED(command->Invoke(items,nullptr)),"Connected multi-file Invoke"); items->Release();
            first=ReceiveFile(*channel); second=ReceiveFile(*channel);
            Require(first.first==a.filename() && second.first==b.filename(),"Connected multi-file transfer");
            channel->Close();
        }
        command->Release(); if(library) FreeLibrary(library);
        std::cout<<(registered || selectionOnly?"PASS: shell menu title, icon, multi-file and ZIP state; real directories hidden; running app and clipboard untouched":"PASS: shell command, malformed IPC rejection, cold share queue, warm single-instance forwarding, Unicode and multi-file transfers with SHA-256; clipboard untouched")<<std::endl;
        WSACleanup(); CoUninitialize(); return 0;
    } catch(const std::exception& error) { std::cerr<<"FAIL: "<<error.what()<<std::endl; WSACleanup(); CoUninitialize(); return 1; }
}
