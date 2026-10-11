#include "Bridge.h"
#include <objbase.h>
#include <ole2.h>
#include <shellapi.h>
#include <gdiplus.h>
#include <bcrypt.h>
#include <ws2tcpip.h>
#include <iostream>
#include <stdexcept>
#include <fstream>
using namespace bridge;
void Require(bool b,const char* m) { if(!b) throw std::runtime_error(m); }
struct ClipboardUnavailable : std::runtime_error { using std::runtime_error::runtime_error; };
struct ClipboardBackup {
    HWND owner=CreateWindowExW(0,L"STATIC",L"test",0,0,0,0,0,HWND_MESSAGE,nullptr,nullptr,nullptr);
    std::vector<std::pair<UINT,HANDLE>> entries;
    ClipboardBackup() {
        Require(OpenClipboard(owner)!=FALSE,"capture clipboard"); UINT f=0;
        while((f=EnumClipboardFormats(f))) {
            auto h=GetClipboardData(f); auto copy=h?OleDuplicateData(h,static_cast<CLIPFORMAT>(f),0):nullptr;
            if(!copy) { CloseClipboard(); throw ClipboardUnavailable("Cannot preserve original clipboard; leaving it unchanged"); }
            entries.push_back({f,copy});
        } CloseClipboard();
    }
    ~ClipboardBackup() { if(OpenClipboard(owner)) { EmptyClipboard(); for(auto [f,h]:entries) SetClipboardData(f,h); CloseClipboard(); } DestroyWindow(owner); }
};
struct Process {
    PROCESS_INFORMATION pi{}; HWND window=nullptr;
    ~Process() { if(window) SendMessageW(window,WM_COMMAND,900,0); if(pi.hProcess) { WaitForSingleObject(pi.hProcess,10000); CloseHandle(pi.hProcess); CloseHandle(pi.hThread); } }
};
std::wstring GetControl(HWND window,int id) {
    wchar_t text[512]{}; DWORD_PTR result;
    Require(SendMessageTimeoutW(GetDlgItem(window,id),WM_GETTEXT,512,reinterpret_cast<LPARAM>(text),SMTO_ABORTIFHUNG,3000,&result)!=0,"read control"); return text;
}
void Snapshot(HWND window,const wchar_t* path) {
    SetWindowPos(window,HWND_BOTTOM,-30000,-30000,0,0,SWP_NOSIZE|SWP_NOACTIVATE);
    ShowWindow(window,SW_SHOWNOACTIVATE); UpdateWindow(window);
    ULONG_PTR token; Gdiplus::GdiplusStartupInput input; Gdiplus::GdiplusStartup(&token,&input,nullptr);
    RECT rect; GetClientRect(window,&rect); auto source=GetDC(window); auto dc=CreateCompatibleDC(source); auto bitmap=CreateCompatibleBitmap(source,rect.right,rect.bottom); auto old=SelectObject(dc,bitmap);
    Require(PrintWindow(window,dc,PW_CLIENTONLY)!=0,"print client");
    SelectObject(dc,old);
    UINT count,size; Gdiplus::GetImageEncodersSize(&count,&size); Bytes bytes(size); auto* encoders=reinterpret_cast<Gdiplus::ImageCodecInfo*>(bytes.data()); Gdiplus::GetImageEncoders(count,size,encoders);
    { Gdiplus::Bitmap image(bitmap,nullptr); for(UINT i=0;i<count;i++) if(wcscmp(encoders[i].MimeType,L"image/png")==0) Require(image.Save(path,&encoders[i].Clsid,nullptr)==Gdiplus::Ok,"snapshot"); }
    DeleteObject(bitmap); DeleteDC(dc); ReleaseDC(window,source); Gdiplus::GdiplusShutdown(token);
}
void CapturePairing(HWND window,const std::filesystem::path& path) {
    Snapshot(window,path.c_str());
    auto uri=Utf8(L"lanbridge://"+GetControl(window,100)+L":42831/"+GetControl(window,101));
    std::ofstream expected(path.wstring()+L".pairing.txt",std::ios::binary); expected<<uri;
    Require(expected.good(),"save expected pairing");
}
int QrSnapshot(const wchar_t* executable,const wchar_t* snapshot) {
    Require(!FindWindowW(L"LanBridgeWindow",nullptr),"app already running"); Process p;
    std::wstring command=L"\""+std::wstring(executable)+L"\""; STARTUPINFOW si{sizeof(si)}; si.dwFlags=STARTF_USESHOWWINDOW; si.wShowWindow=SW_HIDE;
    Require(CreateProcessW(nullptr,command.data(),nullptr,nullptr,FALSE,0,nullptr,nullptr,&si,&p.pi)!=FALSE,"launch QR window"); WaitForInputIdle(p.pi.hProcess,10000);
    p.window=FindWindowW(L"LanBridgeWindow",nullptr); Require(p.window!=nullptr,"QR window");
    Require(GetControl(p.window,101).size()==16,"QR pairing code");
    CapturePairing(p.window,snapshot);
    auto ip=GetDlgItem(p.window,100);
    auto index=SendMessageW(ip,CB_ADDSTRING,0,reinterpret_cast<LPARAM>(L"192.168.77.42"));
    SendMessageW(ip,CB_SETCURSEL,index,0);
    SendMessageW(p.window,WM_COMMAND,MAKEWPARAM(100,CBN_SELCHANGE),reinterpret_cast<LPARAM>(ip));
    Require(GetControl(p.window,100)==L"192.168.77.42","changed address");
    CapturePairing(p.window,std::wstring(snapshot)+L".changed.png");
    std::cout<<"PASS: rendered Win32 QR fixtures for current and changed IPv4; clipboard untouched"<<std::endl;
    return 0;
}
int wmain(int argc,wchar_t** argv) {
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED); WSADATA ws; WSAStartup(MAKEWORD(2,2),&ws);
    try {
        if(argc==4 && wcscmp(argv[1],L"--qr")==0) { auto result=QrSnapshot(argv[2],argv[3]); WSACleanup(); CoUninitialize(); return result; }
        if(argc==3 && wcscmp(argv[1],L"--clipboard-file")==0) {
            ClipboardBackup backup; std::cout<<"READY"<<std::endl;
            std::string command; std::getline(std::cin,command);
            if(command=="stop") { WSACleanup(); CoUninitialize(); return 0; }
            auto files=ReadFiles(backup.owner);
            Require(command=="check" && files.size()==1 && files[0].filename()==argv[2] && std::filesystem::exists(files[0]),"automatic shared file clipboard");
            std::cout<<"PASS: shared file automatically received and copied; restoring original clipboard on exit"<<std::endl;
            std::getline(std::cin,command); WSACleanup(); CoUninitialize(); return 0;
        }
        Require(argc>=2,"need app path"); Require(!FindWindowW(L"LanBridgeWindow",nullptr),"app already running"); ClipboardBackup backup; Process p;
        std::wstring command=L"\""+std::wstring(argv[1])+L"\""; STARTUPINFOW si{sizeof(si)}; si.dwFlags=STARTF_USESHOWWINDOW; si.wShowWindow=SW_HIDE;
        Require(CreateProcessW(nullptr,command.data(),nullptr,nullptr,FALSE,0,nullptr,nullptr,&si,&p.pi)!=FALSE,"launch"); WaitForInputIdle(p.pi.hProcess,10000);
        for(int i=0;i<100 && !p.window;i++) { p.window=FindWindowW(L"LanBridgeWindow",nullptr); Sleep(50); }
        Require(p.window!=nullptr,"window"); auto code=Utf8(GetControl(p.window,101)); Require(code.size()==16,"code"); Sleep(200);
        SOCKET s=::socket(AF_INET,SOCK_STREAM,IPPROTO_TCP); sockaddr_in a{}; a.sin_family=AF_INET; a.sin_addr.s_addr=htonl(INADDR_LOOPBACK); a.sin_port=htons(42831);
        Require(connect(s,reinterpret_cast<sockaddr*>(&a),sizeof(a))==0,"connect"); DWORD timeout=10000; setsockopt(s,SOL_SOCKET,SO_RCVTIMEO,reinterpret_cast<char*>(&timeout),sizeof(timeout));
        Bytes greeting(20); int done=0; while(done<20) { int n=recv(s,reinterpret_cast<char*>(greeting.data()+done),20-done,0); Require(n>0,"greeting"); done+=n; }
        Channel c(s,code,Bytes(greeting.begin()+4,greeting.end()),false); c.Send(1,Bytes{'U','I',' ','t','e','s','t'}); Require(c.Receive().first==1,"hello");
        std::wstring expected=L"自动复制验证：中文与 emoji 😀\n第二行"; auto text=Utf8(expected); c.Send(2,Bytes(text.begin(),text.end()));
        for(int i=0;i<100 && ReadText(backup.owner)!=expected;i++) Sleep(50);
        Require(ReadText(backup.owner)==expected,"auto text clipboard"); Require(c.Receive().first==6,"text receipt");
        auto name=Utf8(L"LanBridge-测试-"+Wide(RandomCode())+L".txt"); Bytes h{static_cast<unsigned char>(name.size()>>8),static_cast<unsigned char>(name.size())}; h.insert(h.end(),name.begin(),name.end());
        for(int i=7;i>=0;i--) h.push_back(static_cast<unsigned char>(static_cast<uint64_t>(text.size())>>(i*8)));
        c.Send(3,h); c.Send(4,Bytes(text.begin(),text.end()));
        BCRYPT_ALG_HANDLE alg=nullptr; BCRYPT_HASH_HANDLE hash=nullptr; Bytes digest(32);
        BCryptOpenAlgorithmProvider(&alg,BCRYPT_SHA256_ALGORITHM,nullptr,0); BCryptCreateHash(alg,&hash,nullptr,0,nullptr,0,0); BCryptHashData(hash,reinterpret_cast<PUCHAR>(text.data()),static_cast<ULONG>(text.size()),0); BCryptFinishHash(hash,digest.data(),32,0); BCryptDestroyHash(hash); BCryptCloseAlgorithmProvider(alg,0);
        c.Send(5,digest); std::vector<std::filesystem::path> files;
        for(int i=0;i<100;i++) { files=ReadFiles(backup.owner); if(files.size()==1 && files[0].filename()==Wide(name)) break; Sleep(50); }
        Require(files.size()==1 && files[0].filename()==Wide(name),"auto CF_HDROP"); Require(std::filesystem::exists(files[0]),"saved file"); Require(c.Receive().first==6,"file receipt");
        SendMessageW(GetDlgItem(p.window,104),WM_SETTEXT,0,reinterpret_cast<LPARAM>(L"Windows UI 回传")); SendMessageW(p.window,WM_COMMAND,105,0);
        auto reply=c.Receive(); Require(reply.first==2 && Wide(std::string(reply.second.begin(),reply.second.end()))==L"Windows UI 回传","reverse UI text");
        Require(SendMessageW(GetDlgItem(p.window,109),LB_GETCOUNT,0,0)==2,"history"); SendMessageW(GetDlgItem(p.window,109),LB_SETCURSEL,1,0); SendMessageW(p.window,WM_COMMAND,110,0); Require(ReadText(backup.owner)==expected,"recopy");
        if(argc>=3) Snapshot(p.window,argv[2]); c.Close(); SendMessageW(p.window,WM_COMMAND,900,0); p.window=nullptr; WaitForSingleObject(p.pi.hProcess,10000); std::filesystem::remove(files[0]);
        std::cout<<"PASS: real Win32 UI auto-copies Unicode text and CF_HDROP, sends text, records/recopies history; original clipboard restored"<<std::endl;
    } catch(const ClipboardUnavailable& e) { std::cout<<"SKIP: "<<e.what()<<std::endl; WSACleanup(); CoUninitialize(); return 77;
    } catch(const std::exception& e) { std::cerr<<"FAIL: "<<e.what()<<std::endl; WSACleanup(); CoUninitialize(); return 1; }
    WSACleanup(); CoUninitialize(); return 0;
}
