#include "Startup.h"
#include "Bridge.h"
#include "ShellTransfer.h"
#include <shellapi.h>
#include <objbase.h>
#include <ws2tcpip.h>
#include <iostream>
#include <stdexcept>

void Require(bool condition,const char* message) { if(!condition) throw std::runtime_error(message); }
struct TemporaryKey {
    std::wstring path;
    TemporaryKey() {
        GUID id{}; CoCreateGuid(&id); wchar_t text[40]{}; StringFromGUID2(id,text,40);
        path=L"Software\\LanBridge\\StartupTest-"+std::wstring(text);
    }
    ~TemporaryKey() {
        if(path.starts_with(L"Software\\LanBridge\\StartupTest-{")) RegDeleteTreeW(HKEY_CURRENT_USER,path.c_str());
    }
};
void RegistryTest() {
    TemporaryKey key; auto executable=std::filesystem::path(L"C:\\中文 folder & name\\LanBridge.exe");
    Require(!startup::ReadCommand(HKEY_CURRENT_USER,key.path.c_str()),"Initially disabled");
    startup::SetEnabled(false,executable,HKEY_CURRENT_USER,key.path.c_str());
    startup::SetEnabled(true,executable,HKEY_CURRENT_USER,key.path.c_str());
    auto command=startup::ReadCommand(HKEY_CURRENT_USER,key.path.c_str());
    Require(command && *command==L"\"C:\\中文 folder & name\\LanBridge.exe\" --startup","Quoted Unicode startup command");
    int count=0; auto arguments=CommandLineToArgvW(command->c_str(),&count);
    Require(arguments && count==2 && arguments[0]==executable && std::wstring(arguments[1])==L"--startup","Startup command parses correctly"); LocalFree(arguments);
    HKEY handle=nullptr; Require(RegOpenKeyExW(HKEY_CURRENT_USER,key.path.c_str(),0,KEY_SET_VALUE|KEY_QUERY_VALUE,&handle)==ERROR_SUCCESS,"Test registry key");
    DWORD other=42; Require(RegSetValueExW(handle,L"OtherProgram",0,REG_DWORD,reinterpret_cast<BYTE*>(&other),sizeof(other))==ERROR_SUCCESS,"Independent startup entry");
    startup::SetEnabled(false,executable,HKEY_CURRENT_USER,key.path.c_str());
    Require(!startup::ReadCommand(HKEY_CURRENT_USER,key.path.c_str()),"Disable removes only LanBridge");
    DWORD type=0,size=sizeof(other),value=0;
    Require(RegQueryValueExW(handle,L"OtherProgram",nullptr,&type,reinterpret_cast<BYTE*>(&value),&size)==ERROR_SUCCESS && value==42,"Other entry preserved");
    RegCloseKey(handle); startup::SetEnabled(false,executable,HKEY_CURRENT_USER,key.path.c_str());
}
struct RegistryBackup {
    bool present=false,restored=false; DWORD type=0; std::vector<BYTE> bytes;
    RegistryBackup() {
        DWORD size=0; auto result=RegGetValueW(HKEY_CURRENT_USER,startup::RunKey,startup::ValueName,RRF_RT_ANY|RRF_NOEXPAND,nullptr,nullptr,&size);
        if(result==ERROR_FILE_NOT_FOUND) return;
        Require(result==ERROR_SUCCESS && size<=65536,"Backup original LanBridge startup entry");
        bytes.resize(size); Require(RegGetValueW(HKEY_CURRENT_USER,startup::RunKey,startup::ValueName,RRF_RT_ANY|RRF_NOEXPAND,&type,bytes.data(),&size)==ERROR_SUCCESS,"Read startup backup"); bytes.resize(size); present=true;
    }
    void Restore() {
        if(restored) return;
        HKEY key=nullptr; Require(RegCreateKeyExW(HKEY_CURRENT_USER,startup::RunKey,0,nullptr,0,KEY_SET_VALUE,nullptr,&key,nullptr)==ERROR_SUCCESS,"Restore registry key");
        auto result=present?RegSetValueExW(key,startup::ValueName,0,type,bytes.data(),static_cast<DWORD>(bytes.size())):RegDeleteValueW(key,startup::ValueName);
        RegCloseKey(key); Require(result==ERROR_SUCCESS || (!present && result==ERROR_FILE_NOT_FOUND),"Restore original startup entry"); restored=true;
    }
    ~RegistryBackup() { try { Restore(); } catch(...) { std::cerr<<"ERROR: failed to restore startup entry"<<std::endl; } }
};
struct Process {
    HANDLE handle=nullptr; DWORD id=0; HWND window=nullptr;
    ~Process() {
        DWORD owner=0; if(window) GetWindowThreadProcessId(window,&owner);
        if(owner==id && window) SendMessageTimeoutW(window,WM_COMMAND,900,0,SMTO_ABORTIFHUNG,3000,nullptr);
        if(handle) { WaitForSingleObject(handle,10000); CloseHandle(handle); }
    }
};
void Launch(Process& process,const wchar_t* executable,bool startupLaunch) {
    auto command=shelltransfer::QuoteArgument(executable)+(startupLaunch?L" --startup":L"");
    STARTUPINFOW startup{sizeof(startup)}; startup.dwFlags=STARTF_USESHOWWINDOW; startup.wShowWindow=SW_SHOWNORMAL;
    PROCESS_INFORMATION info{}; Require(CreateProcessW(executable,command.data(),nullptr,nullptr,FALSE,0,nullptr,nullptr,&startup,&info)!=FALSE,"Launch app");
    process.handle=info.hProcess; process.id=info.dwProcessId; CloseHandle(info.hThread);
}
void ExitSucceeded(Process& process) {
    Require(WaitForSingleObject(process.handle,10000)==WAIT_OBJECT_0,"Duplicate startup exited");
    DWORD code=1; Require(GetExitCodeProcess(process.handle,&code) && code==0,"Duplicate launch success");
}
std::unique_ptr<bridge::Channel> Pair(HWND window) {
    wchar_t code[64]{}; SendMessageW(GetDlgItem(window,101),WM_GETTEXT,64,reinterpret_cast<LPARAM>(code));
    SOCKET socket=INVALID_SOCKET;
    for(int i=0;i<100;i++) {
        socket=::socket(AF_INET,SOCK_STREAM,IPPROTO_TCP); sockaddr_in address{}; address.sin_family=AF_INET; address.sin_addr.s_addr=htonl(INADDR_LOOPBACK); address.sin_port=htons(42831);
        if(connect(socket,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0) break;
        closesocket(socket); socket=INVALID_SOCKET; Sleep(50);
    }
    Require(socket!=INVALID_SOCKET,"Tray startup listens for connections");
    DWORD timeout=10000; setsockopt(socket,SOL_SOCKET,SO_RCVTIMEO,reinterpret_cast<char*>(&timeout),sizeof(timeout));
    bridge::Bytes greeting(20); int done=0;
    while(done<20) { auto size=recv(socket,reinterpret_cast<char*>(greeting.data()+done),20-done,0); Require(size>0,"Server greeting"); done+=size; }
    auto channel=std::make_unique<bridge::Channel>(socket,bridge::Utf8(code),bridge::Bytes(greeting.begin()+4,greeting.end()),false);
    channel->Send(1,bridge::Bytes{'S','t','a','r','t','u','p',' ','t','e','s','t'}); Require(channel->Receive().first==1,"Pair tray app"); return channel;
}
void Heartbeat(bridge::Channel& channel) {
    channel.Send(7);
    while(true) { auto [type,bytes]=channel.Receive(); if(type==7) { channel.Send(8); continue; } Require(type==8,"Connection survived duplicate startup"); return; }
}
BOOL CALLBACK FindPopup(HWND window,LPARAM result) {
    wchar_t name[64]{}; GetClassNameW(window,name,64);
    if(std::wstring(name)==L"#32768" && IsWindowVisible(window)) *reinterpret_cast<HWND*>(result)=window;
    return TRUE;
}
int wmain(int argc,wchar_t** argv) {
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED); WSADATA winsock{}; WSAStartup(MAKEWORD(2,2),&winsock);
    try {
        RegistryTest();
        if(argc==2 && std::wstring(argv[1])==L"--registry-only") { std::cout<<"PASS: isolated Win32 startup registry enable/disable and Unicode paths"<<std::endl; WSACleanup(); CoUninitialize(); return 0; }
        Require(argc==2 && !FindWindowW(L"LanBridgeWindow",nullptr),"Expected app path and no running LanBridge");
        RegistryBackup backup; Process app; Launch(app,argv[1],true); WaitForInputIdle(app.handle,10000);
        for(int i=0;i<100 && !app.window;i++) { app.window=FindWindowW(L"LanBridgeWindow",nullptr); if(!app.window) Sleep(50); }
        Require(app.window && !IsWindowVisible(app.window),"Startup window stays hidden despite normal show command");
        auto channel=Pair(app.window); Heartbeat(*channel);
        { Process duplicate; Launch(duplicate,argv[1],true); ExitSucceeded(duplicate); }
        Require(!IsWindowVisible(app.window) && WaitForSingleObject(app.handle,0)==WAIT_TIMEOUT,"Startup duplicate neither replaces nor reveals existing app"); Heartbeat(*channel);
        { Process manual; Launch(manual,argv[1],false); ExitSucceeded(manual); }
        Require(IsWindowVisible(app.window) && WaitForSingleObject(app.handle,0)==WAIT_TIMEOUT,"Manual open restores original app"); Heartbeat(*channel);
        auto minimize=GetDlgItem(app.window,0x7001),maximize=GetDlgItem(app.window,0x7002),close=GetDlgItem(app.window,0x7003);
        Require(minimize && maximize && close,"Custom title bar buttons");
        HFONT titleFont=reinterpret_cast<HFONT>(SendMessageW(close,WM_GETFONT,0,0)); LOGFONTW title{};
        Require(titleFont && GetObjectW(titleFont,sizeof(title),&title)==sizeof(title) && std::wstring(title.lfFaceName)==L"Microsoft YaHei UI","Title font matches UI");
        RECT client{}; GetClientRect(app.window,&client);
        POINT drag{client.right/2,20*static_cast<int>(GetDpiForWindow(app.window))/96}; ClientToScreen(app.window,&drag);
        Require(SendMessageW(app.window,WM_NCHITTEST,0,MAKELPARAM(drag.x,drag.y))==HTCAPTION,"Custom caption remains draggable");
        SendMessageW(maximize,BM_CLICK,0,0); Require(IsZoomed(app.window),"Custom maximize button"); Heartbeat(*channel);
        SendMessageW(maximize,BM_CLICK,0,0); Require(!IsZoomed(app.window),"Custom restore button");
        SendMessageW(minimize,BM_CLICK,0,0); Require(IsIconic(app.window),"Custom minimize button");
        SendMessageW(app.window,WM_SYSCOMMAND,SC_RESTORE,0); Heartbeat(*channel);
        RECT work{}; SystemParametersInfoW(SPI_GETWORKAREA,0,&work,0);
        PostMessageW(app.window,WM_APP+2,MAKELPARAM(work.left+100,work.top+100),MAKELPARAM(WM_CONTEXTMENU,1));
        HWND menu=nullptr; auto thread=GetWindowThreadProcessId(app.window,nullptr);
        for(int i=0;i<100 && !menu;i++) { EnumThreadWindows(thread,FindPopup,reinterpret_cast<LPARAM>(&menu)); if(!menu) Sleep(50); }
        Require(menu!=nullptr,"Actual tray menu opens"); Heartbeat(*channel);
        SendMessageW(app.window,WM_CANCELMODE,0,0); Heartbeat(*channel);
        auto checkbox=GetDlgItem(app.window,116); Require(checkbox!=nullptr,"Startup checkbox");
        SendMessageW(checkbox,BM_SETCHECK,BST_UNCHECKED,0); SendMessageW(checkbox,BM_CLICK,0,0);
        auto nativePath=std::filesystem::path(argv[1]); nativePath.make_preferred();
        Require(startup::ReadCommand()==startup::CommandLine(nativePath) && SendMessageW(checkbox,BM_GETCHECK,0,0)==BST_CHECKED,"Actual UI enables startup for current executable");
        SendMessageW(checkbox,BM_CLICK,0,0); Require(!startup::ReadCommand() && SendMessageW(checkbox,BM_GETCHECK,0,0)==BST_UNCHECKED,"Actual UI disables startup");
        SendMessageW(close,BM_CLICK,0,0); Require(!IsWindowVisible(app.window) && WaitForSingleObject(app.handle,0)==WAIT_TIMEOUT,"Caption close keeps app in tray");
        Heartbeat(*channel); channel->Close(); backup.Restore();
        std::cout<<"PASS: startup to tray, encrypted background connection, duplicate startup preserves session, manual launch restores window, caption font/actions and tray popup preserve connection, UI registry enable/disable; original startup entry restored; clipboard untouched"<<std::endl;
        WSACleanup(); CoUninitialize(); return 0;
    } catch(const std::exception& error) { std::cerr<<"FAIL: "<<error.what()<<std::endl; WSACleanup(); CoUninitialize(); return 1; }
}
