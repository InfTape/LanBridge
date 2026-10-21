#include "Bridge.h"
#include "FolderTransfer.h"
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
bool OpenTestClipboard(HWND owner) {
    for(int i=0;i<20;++i) { if(OpenClipboard(owner)) return true; Sleep(30); }
    return false;
}
struct ClipboardBackup {
    HWND owner=CreateWindowExW(0,L"STATIC",L"test",0,0,0,0,0,HWND_MESSAGE,nullptr,nullptr,nullptr);
    std::vector<std::pair<UINT,HANDLE>> entries;
    ClipboardBackup() {
        Require(OpenTestClipboard(owner),"capture clipboard"); UINT f=0;
        while((f=EnumClipboardFormats(f))) {
            auto h=GetClipboardData(f); auto copy=h?OleDuplicateData(h,static_cast<CLIPFORMAT>(f),0):nullptr;
            if(!copy) { CloseClipboard(); throw ClipboardUnavailable("Cannot preserve original clipboard; leaving it unchanged"); }
            entries.push_back({f,copy});
        } CloseClipboard();
    }
    ~ClipboardBackup() { if(OpenTestClipboard(owner)) { EmptyClipboard(); for(auto [f,h]:entries) SetClipboardData(f,h); CloseClipboard(); } DestroyWindow(owner); }
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
void SetTestBitmap(HWND owner,DWORD color) {
    constexpr size_t pixels=3*2;
    auto memory=GlobalAlloc(GMEM_MOVEABLE|GMEM_ZEROINIT,sizeof(BITMAPINFOHEADER)+pixels*sizeof(DWORD));
    Require(memory!=nullptr,"allocate bitmap fixture");
    auto header=static_cast<BITMAPINFOHEADER*>(GlobalLock(memory));
    Require(header!=nullptr,"lock bitmap fixture");
    header->biSize=sizeof(*header); header->biWidth=3; header->biHeight=-2;
    header->biPlanes=1; header->biBitCount=32; header->biCompression=BI_RGB;
    auto data=reinterpret_cast<DWORD*>(header+1);
    for(size_t i=0;i<pixels;++i) data[i]=color+static_cast<DWORD>(i);
    GlobalUnlock(memory);
    Require(OpenTestClipboard(owner),"open bitmap clipboard");
    Require(EmptyClipboard() && SetClipboardData(CF_DIB,memory),"publish DIB fixture"); CloseClipboard();
}
Bytes FileBytes(const std::filesystem::path& file) {
    std::ifstream stream(file,std::ios::binary);
    Require(stream.good(),"open PNG snapshot"); return Bytes(std::istreambuf_iterator<char>(stream),{});
}
Bytes Digest(const Bytes& data) {
    BCRYPT_ALG_HANDLE algorithm=nullptr; Bytes digest(32);
    Require(BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,nullptr,0)==0,"hash provider");
    auto status=BCryptHash(algorithm,nullptr,0,const_cast<PUCHAR>(data.data()),static_cast<ULONG>(data.size()),digest.data(),32);
    BCryptCloseAlgorithmProvider(algorithm,0); Require(status==0,"hash PNG"); return digest;
}
std::unique_ptr<Channel> ConnectUi(HWND window) {
    SOCKET socket=::socket(AF_INET,SOCK_STREAM,IPPROTO_TCP); sockaddr_in address{};
    address.sin_family=AF_INET; address.sin_addr.s_addr=htonl(INADDR_LOOPBACK); address.sin_port=htons(42831);
    Require(connect(socket,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0,"queue client connect");
    DWORD timeout=5000; setsockopt(socket,SOL_SOCKET,SO_RCVTIMEO,reinterpret_cast<char*>(&timeout),sizeof(timeout));
    Bytes greeting(20); int received=0;
    while(received<20) { auto n=recv(socket,reinterpret_cast<char*>(greeting.data()+received),20-received,0); Require(n>0,"queue greeting"); received+=n; }
    auto channel=std::make_unique<Channel>(socket,Utf8(GetControl(window,101)),Bytes(greeting.begin()+4,greeting.end()),false);
    channel->Send(1,Bytes{'Q','u','e','u','e'}); Require(channel->Receive().first==1,"queue authenticated hello"); return channel;
}
void ReceivePng(Channel& channel,const Bytes& expected) {
    auto [type,header]=channel.Receive(); Require(type==3 && header.size()>10,"queued PNG header");
    size_t length=(static_cast<size_t>(header[0])<<8)|header[1]; Require(header.size()==length+10,"PNG header length");
    auto name=Wide(std::string(header.begin()+2,header.begin()+2+length)); Require(name.ends_with(L".png"),"PNG name");
    uint64_t size=0; for(size_t i=2+length;i<header.size();++i) size=(size<<8)|header[i];
    Require(size==expected.size(),"snapshot byte count"); Bytes actual;
    while(actual.size()<size) { auto part=channel.Receive(); Require(part.first==4,"PNG chunk"); actual.insert(actual.end(),part.second.begin(),part.second.end()); }
    Require(actual==expected,"queued image preserves original pixels after clipboard changes");
    auto digest=channel.Receive(); Require(digest.first==5 && digest.second==Digest(actual),"PNG SHA256");
    auto temporary=std::filesystem::temp_directory_path()/name;
    for(int i=0;i<100 && std::filesystem::exists(temporary);++i) Sleep(10);
    Require(!std::filesystem::exists(temporary),"sent image temporary file cleaned");
}
int QueuedClipboard(const wchar_t* executable) {
    Require(!FindWindowW(L"LanBridgeWindow",nullptr),"app already running"); ClipboardBackup backup; Process process;
    auto command=L"\""+std::wstring(executable)+L"\" --startup"; STARTUPINFOW info{sizeof(info)};
    Require(CreateProcessW(nullptr,command.data(),nullptr,nullptr,FALSE,0,nullptr,nullptr,&info,&process.pi)!=FALSE,"launch queue test");
    WaitForInputIdle(process.pi.hProcess,10000);
    for(int i=0;i<100 && !process.window;++i) { process.window=FindWindowW(L"LanBridgeWindow",nullptr); if(!process.window) Sleep(20); }
    Require(process.window!=nullptr,"queue window");
    auto queueImage=[&](DWORD color) {
        SetTestBitmap(backup.owner,color); auto snapshot=ReadImage(backup.owner); Require(snapshot!=nullptr,"DIB converts to PNG");
        auto bytes=FileBytes(*snapshot);
        SendMessageW(process.window,WM_COMMAND,106,0);
        Require(GetControl(process.window,113).find(L"待发送队列")!=std::wstring::npos,"offline clipboard is queued");
        return bytes;
    };
    const auto first=queueImage(0x00112233),second=queueImage(0x00aabb00);
    Require(CopyText(backup.owner,L"queued clipboard text"),"set queued text"); SendMessageW(process.window,WM_COMMAND,106,0);
    Require(CopyText(backup.owner,L"replacement must not be sent"),"replace queued clipboard");
    SendMessageW(GetDlgItem(process.window,104),WM_SETTEXT,0,reinterpret_cast<LPARAM>(L"queued editor text"));
    SendMessageW(process.window,WM_COMMAND,105,0);
    auto channel=ConnectUi(process.window); ReceivePng(*channel,first); ReceivePng(*channel,second);
    for(const auto& expected:{"queued clipboard text","queued editor text"}) {
        auto result=channel->Receive(); Require(result.first==2 && std::string(result.second.begin(),result.second.end())==expected,"queued text order and snapshot");
    }
    channel->Send(7); Require(channel->Receive().first==8,"queue sent exactly once"); channel->Close();
    for(int i=0;i<100 && GetControl(process.window,113).find(L"连接已断开")==std::wstring::npos;++i) Sleep(20);
    Require(GetControl(process.window,113).find(L"连接已断开")!=std::wstring::npos,"disconnected before second queue");
    auto third=queueImage(0x00334455); Require(CopyText(backup.owner,L"changed again"),"change clipboard before reconnect");
    channel=ConnectUi(process.window); ReceivePng(*channel,third);
    channel->Send(7); Require(channel->Receive().first==8,"reconnect sends exactly once"); channel->Close();
    std::cout<<"PASS: offline DIB images and text queued in order, clipboard snapshots survive replacement, authenticated reconnect drains once, PNG bytes/SHA256 and temporary cleanup verified; original clipboard restored"<<std::endl;
    return 0;
}
int wmain(int argc,wchar_t** argv) {
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED); WSADATA ws; WSAStartup(MAKEWORD(2,2),&ws);
    try {
        if(argc==3 && wcscmp(argv[1],L"--queued-clipboard")==0) { auto result=QueuedClipboard(argv[2]); WSACleanup(); CoUninitialize(); return result; }
        if(argc==3 && wcscmp(argv[1],L"--addresses")==0) {
            Require(!FindWindowW(L"LanBridgeWindow",nullptr),"app already running"); Process p;
            std::wstring command=L"\""+std::wstring(argv[2])+L"\" --startup";
            STARTUPINFOW info{sizeof(info)};
            Require(CreateProcessW(nullptr,command.data(),nullptr,nullptr,FALSE,0,nullptr,nullptr,&info,&p.pi)!=FALSE,"launch address refresh app");
            WaitForInputIdle(p.pi.hProcess,10000);
            for(int i=0;i<100 && !p.window;++i) { p.window=FindWindowW(L"LanBridgeWindow",nullptr); if(!p.window) Sleep(50); }
            Require(p.window && !IsWindowVisible(p.window),"hidden startup window");
            const auto originalCode=GetControl(p.window,101);
            auto addresses=LocalAddresses();
            const auto expected=!addresses.empty() && addresses.front().autoSelect?addresses.front().address:L"请选择局域网 IPv4 地址";
            auto combo=GetDlgItem(p.window,100);
            const auto stale=[&](const wchar_t* value) {
                SendMessageW(combo,CB_RESETCONTENT,0,0); SendMessageW(combo,CB_ADDSTRING,0,reinterpret_cast<LPARAM>(value)); SendMessageW(combo,CB_SETCURSEL,0,0);
                SendMessageW(p.window,WM_COMMAND,MAKEWPARAM(100,CBN_SELCHANGE),reinterpret_cast<LPARAM>(combo));
            };
            const auto refresh=[&](const std::wstring& value) {
                PostMessageW(p.window,WM_APP+3,0,0);
                for(int i=0;i<100 && GetControl(p.window,100)!=value;++i) Sleep(30);
                Require(GetControl(p.window,100)==value,"network change restores current address");
            };
            stale(L"请选择局域网 IPv4 地址");
            Require(!IsWindowEnabled(GetDlgItem(p.window,102)),"missing address disables copying invalid link");
            refresh(expected);
            Require(IsWindowEnabled(GetDlgItem(p.window,102))==(expected.find(L"地址")==std::wstring::npos),"QR readiness follows refreshed address");
            // Keep a real encrypted session while refreshing the visible list.
            SOCKET socket=::socket(AF_INET,SOCK_STREAM,IPPROTO_TCP); sockaddr_in endpoint{}; endpoint.sin_family=AF_INET; endpoint.sin_addr.s_addr=htonl(INADDR_LOOPBACK); endpoint.sin_port=htons(42831);
            Require(connect(socket,reinterpret_cast<sockaddr*>(&endpoint),sizeof(endpoint))==0,"connect refresh client");
            DWORD timeout=10000; setsockopt(socket,SOL_SOCKET,SO_RCVTIMEO,reinterpret_cast<char*>(&timeout),sizeof(timeout));
            Bytes greeting(20); int count=0; while(count<20) {int n=recv(socket,reinterpret_cast<char*>(greeting.data()+count),20-count,0); Require(n>0,"refresh greeting"); count+=n;}
            Channel channel(socket,Utf8(originalCode),Bytes(greeting.begin()+4,greeting.end()),false);
            channel.Send(1,Bytes{'A','d','d','r','e','s','s',' ','t','e','s','t'}); Require(channel.Receive().first==1,"refresh encrypted handshake");
            for(const auto& address:addresses) if(!address.autoSelect) {
                auto index=SendMessageW(combo,CB_FINDSTRINGEXACT,-1,reinterpret_cast<LPARAM>(address.address.c_str())); Require(index!=CB_ERR,"manual adapter listed");
                SendMessageW(combo,CB_SETCURSEL,index,0); SendMessageW(p.window,WM_COMMAND,MAKEWPARAM(100,CBN_SELCHANGE),reinterpret_cast<LPARAM>(combo));
                PostMessageW(p.window,WM_APP+3,0,0); Sleep(400);
                Require(GetControl(p.window,100)==address.address,"refresh preserves explicit virtual adapter choice"); break;
            }
            stale(L"192.0.2.123"); refresh(expected);
            stale(L"请选择局域网 IPv4 地址"); ShowWindow(p.window,SW_SHOWNOACTIVATE);
            Require(GetControl(p.window,100)==expected,"opening hidden window repairs stale address immediately");
            channel.Send(7);
            while(true) {auto [type,data]=channel.Receive(); if(type==7) {channel.Send(8);continue;} Require(type==8,"encrypted connection survives address refresh");break;}
            Require(GetControl(p.window,101)==originalCode,"refresh preserves pairing credential");
            channel.Close();
            std::cout<<"PASS: delayed LAN snapshot recovers, valid QR/link readiness, explicit virtual address preserved, removed address returns to physical default, window show refreshes, same encrypted connection and pairing survive; clipboard untouched"<<std::endl;
            WSACleanup(); CoUninitialize(); return 0;
        }
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
        Channel c(s,code,Bytes(greeting.begin()+4,greeting.end()),false); std::string hello="UI test\nLB-FOLDER1"; c.Send(1,Bytes(hello.begin(),hello.end())); Require(c.Receive().first==1,"hello");
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
        auto folderName="LanBridge-folder-ui-"+RandomCode();
        c.Send(9,folders::EncodeHeader({42,folderName,1,1,static_cast<uint64_t>(text.size())})); auto ready=c.Receive(); Require(ready.first==14 && folders::Accepted(ready.second),"folder accepted");
        c.Send(10,Bytes{'e','m','p','t','y'}); c.Send(11,folders::FileHeader("content.txt",text.size())); c.Send(4,Bytes(text.begin(),text.end())); c.Send(5,digest);
        auto folderClipboard=ReadFiles(backup.owner); Require(folderClipboard.empty() || folderClipboard.front().filename()!=Wide(folderName),"partial folder copied prematurely");
        auto end=folders::Reply(42,true); end.resize(8); c.Send(12,end); auto completed=c.Receive(); Require(completed.first==15 && folders::Accepted(completed.second),"folder completed"); Require(c.Receive().first==6,"folder clipboard receipt");
        folderClipboard=ReadFiles(backup.owner); Require(folderClipboard.size()==1 && folderClipboard.front().filename()==Wide(folderName) && std::filesystem::is_directory(folderClipboard.front()/L"empty"),"whole folder CF_HDROP");
        Require(SendMessageW(GetDlgItem(p.window,109),LB_GETCOUNT,0,0)==3,"folder history"); SendMessageW(p.window,WM_COMMAND,110,0); Require(ReadFiles(backup.owner)==folderClipboard,"folder recopy");
        if(argc>=3) Snapshot(p.window,argv[2]); c.Close(); SendMessageW(p.window,WM_COMMAND,900,0); p.window=nullptr; WaitForSingleObject(p.pi.hProcess,10000); std::filesystem::remove(files[0]);
        Require(std::filesystem::equivalent(folderClipboard.front().parent_path(),ReceiveDirectory()) && folderClipboard.front().filename()==Wide(folderName),"folder cleanup boundary"); std::filesystem::remove_all(folderClipboard.front());
        std::cout<<"PASS: real Win32 UI auto-copies Unicode text, file and completed folder CF_HDROP, sends text, records/recopies history; original clipboard restored"<<std::endl;
    } catch(const ClipboardUnavailable& e) { std::cout<<"SKIP: "<<e.what()<<std::endl; WSACleanup(); CoUninitialize(); return 77;
    } catch(const std::exception& e) { std::cerr<<"FAIL: "<<e.what()<<std::endl; WSACleanup(); CoUninitialize(); return 1; }
    WSACleanup(); CoUninitialize(); return 0;
}
