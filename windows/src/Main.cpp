#include "Bridge.h"
#include "ShellTransfer.h"
#include "Startup.h"
#include "AppIcon.h"
#include "ui/UiTypography.h"
#include "ui/TitleBar.h"
#include "ui/PopupMenu.h"
#include <commctrl.h>
#include <commdlg.h>
#include <dwmapi.h>
#include <shellapi.h>
#include <objbase.h>
#include <windowsx.h>
#include <algorithm>
#include <deque>
#include <optional>
#include <fstream>
#include "qrcodegen.hpp"

namespace {
constexpr UINT EventsMessage=WM_APP+1,TrayMessage=WM_APP+2;
const UINT TaskbarCreatedMessage=RegisterWindowMessageW(L"TaskbarCreated");
enum ControlId { Ip=100,Code,CopyLink,Disconnect,Text,SendText,SendClipboard,SendFile,OpenFolder,History,Recopy,Clear,Progress,Status,EnableMenu,DisableMenu,Startup,Qr=200,Exit=900 };
struct Record { bool file; std::wstring text; std::filesystem::path path; };
class App {
    HWND window_=nullptr;
    std::unique_ptr<bridge::Server> server_;
    std::filesystem::path folder_;
    std::string code_;
    std::optional<qrcodegen::QrCode> qr_;
    std::vector<std::pair<HWND,RECT>> controls_;
    std::vector<HFONT> fonts_;
    std::deque<Record> records_;
    std::mutex eventMutex_;
    std::deque<bridge::Event> events_;
    std::thread sender_;
    std::thread integration_;
    std::atomic<bool> sending_{false};
    std::atomic<bool> integrating_{false};
    std::deque<shelltransfer::Paths> pendingFiles_;
    HBRUSH background_=CreateSolidBrush(RGB(247,249,252));
    HBRUSH card_=CreateSolidBrush(RGB(255,255,255));
    NOTIFYICONDATAW tray_{};
    int dpi_=96;
    bool exiting_=false;
    bool receiving_=false;
    bool senderProgress_=false;
    bool startupEnabled_=false;
    bool trayVersion4_=false;
    ui::TitleBar titleBar_;
    ui::PopupMenu popup_;
public:
    ~App() { if(server_) server_->Stop(); if(sender_.joinable()) sender_.join(); if(integration_.joinable()) integration_.join(); for(auto f:fonts_) DeleteObject(f); DeleteObject(background_); DeleteObject(card_); }
    int Run(HINSTANCE instance,int show,const shelltransfer::Paths& initial = {}) {
        folder_=bridge::ReceiveDirectory(); code_=bridge::RandomCode();
        WNDCLASSEXW wc{sizeof(wc)}; wc.lpfnWndProc=Proc; wc.hInstance=instance; wc.hCursor=LoadCursorW(nullptr,IDC_ARROW);
        wc.hIcon=LoadAppIcon(GetSystemMetricsForDpi(SM_CXICON,GetDpiForSystem())); wc.hIconSm=LoadAppIcon(GetSystemMetricsForDpi(SM_CXSMICON,GetDpiForSystem())); wc.lpszClassName=L"LanBridgeWindow";
        RegisterClassExW(&wc);
        RECT area{}; SystemParametersInfoW(SPI_GETWORKAREA,0,&area,0);
        int scale=GetDpiForSystem();
        int width=std::min(MulDiv(1060,scale,96),static_cast<int>(area.right-area.left));
        int height=std::min(MulDiv(820,scale,96),static_cast<int>(area.bottom-area.top));
        window_=CreateWindowExW(WS_EX_ACCEPTFILES,wc.lpszClassName,L"LanBridge · 局域网互传",WS_OVERLAPPEDWINDOW,CW_USEDEFAULT,CW_USEDEFAULT,width,height,nullptr,nullptr,instance,this);
        if(!window_) return 1;
        server_=std::make_unique<bridge::Server>(folder_,[this](bridge::Event e) { Queue(std::move(e)); });
        server_->Start(42831,code_);
        ShowWindow(window_,show); UpdateWindow(window_);
        if(!initial.empty()) AcceptFiles(initial);
        MSG m; while(GetMessageW(&m,nullptr,0,0)>0) { if(!IsDialogMessageW(window_,&m)) { TranslateMessage(&m); DispatchMessageW(&m); } }
        return static_cast<int>(m.wParam);
    }
private:
    int S(int x) const { return MulDiv(x,dpi_,96); }
    HWND Item(int id) { return GetDlgItem(window_,id); }
    void Queue(bridge::Event e) {
        { std::lock_guard lock(eventMutex_); events_.push_back(std::move(e)); }
        PostMessageW(window_,EventsMessage,0,0);
    }
    void FontSetup() {
        for(auto f:fonts_) DeleteObject(f); fonts_.clear();
        for(int height:{14,24,18}) fonts_.push_back(ui::CreateUiFont(dpi_,height,height==24?FW_SEMIBOLD:FW_NORMAL));
        for(auto [h,r]:controls_) SendMessageW(h,WM_SETFONT,reinterpret_cast<WPARAM>(fonts_[0]),TRUE);
        titleBar_.SetFont(fonts_[0]);
    }
    HWND Control(const wchar_t* cls,const wchar_t* text,int id,DWORD style=0,DWORD ex=0) {
        auto h=CreateWindowExW(ex,cls,text,WS_CHILD|WS_VISIBLE|style,0,0,0,0,window_,reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)),GetModuleHandleW(nullptr),nullptr);
        controls_.push_back({h,{}}); return h;
    }
    void Create() {
        dpi_=GetDpiForWindow(window_);
        if(!titleBar_.Create(window_,GetModuleHandleW(nullptr))) throw std::runtime_error("无法创建标题栏");
        Control(L"STATIC",L"LanBridge",10);
        Control(L"STATIC",L"同一网络，随手互传。",11);
        Control(L"BUTTON",L"启用右键发送",EnableMenu,WS_TABSTOP);
        Control(L"BUTTON",L"移除右键发送",DisableMenu,WS_TABSTOP);
        Control(L"BUTTON",L"开机启动（默认收至托盘）",Startup,BS_AUTOCHECKBOX|WS_TABSTOP);
        try { startupEnabled_=startup::IsEnabled(startup::ExecutablePath()); }
        catch(const std::exception& e) { Queue({bridge::Event::Error,bridge::Wide(e.what())}); }
        SendMessageW(Item(Startup),BM_SETCHECK,startupEnabled_?BST_CHECKED:BST_UNCHECKED,0);
        Control(L"STATIC",L"01   扫码连接",12);
        Control(L"STATIC",L"配对二维码",Qr,SS_OWNERDRAW);
        Control(L"STATIC",L"电脑地址",13);
        Control(L"COMBOBOX",L"",Ip,CBS_DROPDOWNLIST|WS_VSCROLL|WS_TABSTOP);
        auto addresses=bridge::LocalAddresses();
        if(addresses.empty() || !addresses.front().autoSelect) SendMessageW(Item(Ip),CB_ADDSTRING,0,reinterpret_cast<LPARAM>(L"请选择局域网 IPv4 地址"));
        for(const auto& a:addresses) SendMessageW(Item(Ip),CB_ADDSTRING,0,reinterpret_cast<LPARAM>(a.address.c_str()));
        SendMessageW(Item(Ip),CB_SETCURSEL,0,0);
        Control(L"STATIC",L"配对码（手动连接备用）",14);
        Control(L"EDIT",bridge::Wide(code_).c_str(),Code,ES_READONLY|ES_CENTER|WS_TABSTOP,WS_EX_CLIENTEDGE);
        Control(L"BUTTON",L"复制连接信息",CopyLink,WS_TABSTOP);
        Control(L"BUTTON",L"断开设备",Disconnect,WS_TABSTOP);
        Control(L"STATIC",L"手机扫描上方二维码即可连接",15);
        Control(L"STATIC",L"02   发送到手机",16);
        Control(L"EDIT",L"",Text,WS_TABSTOP|ES_MULTILINE|ES_AUTOVSCROLL|WS_VSCROLL|ES_WANTRETURN,WS_EX_CLIENTEDGE);
        SendMessageW(Item(Text),EM_SETCUEBANNER,TRUE,reinterpret_cast<LPARAM>(L"输入要发送的文本…"));
        SendMessageW(Item(Text),EM_SETLIMITTEXT,120000,0);
        Control(L"BUTTON",L"发送文本",SendText,WS_TABSTOP);
        Control(L"BUTTON",L"发送剪贴板",SendClipboard,WS_TABSTOP);
        Control(L"BUTTON",L"选择文件…",SendFile,WS_TABSTOP);
        Control(L"STATIC",L"支持拖入多个文件；文件夹请先压缩。",17);
        Control(L"STATIC",L"03   最近收到",18);
        Control(L"STATIC",L"接收完成后自动复制到剪贴板，可直接 Ctrl + V。",19);
        Control(L"LISTBOX",L"",History,WS_TABSTOP|WS_VSCROLL|LBS_NOINTEGRALHEIGHT|LBS_NOTIFY,WS_EX_CLIENTEDGE);
        Control(L"BUTTON",L"重新复制",Recopy,WS_TABSTOP);
        Control(L"BUTTON",L"打开接收目录",OpenFolder,WS_TABSTOP);
        Control(L"BUTTON",L"清空记录",Clear,WS_TABSTOP);
        Control(PROGRESS_CLASSW,L"",Progress,PBS_SMOOTH);
        Control(L"STATIC",L"启动中…",Status);
        UpdateQr(); FontSetup(); Layout();
        BOOL dark=FALSE; DwmSetWindowAttribute(window_,20,&dark,sizeof(dark));
        tray_.cbSize=sizeof(tray_); tray_.hWnd=window_; tray_.uID=1; tray_.uFlags=NIF_ICON|NIF_MESSAGE|NIF_TIP; tray_.uCallbackMessage=TrayMessage; tray_.hIcon=LoadAppIcon(GetSystemMetricsForDpi(SM_CXSMICON,dpi_));
        wcscpy_s(tray_.szTip,L"LanBridge · 局域网互传"); Shell_NotifyIconW(NIM_ADD,&tray_);
        tray_.uVersion=NOTIFYICON_VERSION_4; trayVersion4_=Shell_NotifyIconW(NIM_SETVERSION,&tray_)!=FALSE;
    }
    void Place(int id,int x,int y,int w,int h) { MoveWindow(Item(id),S(x),S(y+ui::TitleBar::kHeight),S(w),S(h),TRUE); }
    void Layout() {
        RECT rc; GetClientRect(window_,&rc); int w=MulDiv(rc.right,96,dpi_),h=MulDiv(rc.bottom,96,dpi_)-ui::TitleBar::kHeight;
        titleBar_.Layout(rc.right);
        int right=w-364,editor=right-205;
        int qrEdge=std::clamp(h-448,208,246),afterQr=151+qrEdge;
        Place(10,32,22,300,38); Place(11,34,63,500,26);
        Place(EnableMenu,w-360,32,155,33); Place(DisableMenu,w-195,32,155,33);
        Place(Startup,w-360,68,310,24);
        Place(12,40,111,246,28); Place(Qr,40+(246-qrEdge)/2,151,qrEdge,qrEdge);
        Place(15,40,afterQr+5,246,28);
        Place(13,40,afterQr+42,80,25); Place(Ip,124,afterQr+38,162,240);
        Place(14,40,afterQr+78,246,25); Place(Code,40,afterQr+107,246,31);
        Place(CopyLink,40,afterQr+151,246,33); Place(Disconnect,40,afterQr+194,246,33);
        Place(16,324,111,right,28); Place(Text,324,151,editor,123);
        Place(SendText,w-230,151,190,32); Place(SendClipboard,w-230,193,190,32); Place(SendFile,w-230,235,190,32);
        Place(17,324,289,right,25);
        Place(18,324,354,right,28); Place(19,324,394,right,26);
        Place(History,324,430,editor,std::max(75,h-494)); Place(Recopy,w-230,430,190,32); Place(OpenFolder,w-230,468,190,32); Place(Clear,w-230,506,190,32);
        Place(Progress,32,h-49,w-64,8); Place(Status,32,h-33,w-64,26);
        SendMessageW(Item(10),WM_SETFONT,reinterpret_cast<WPARAM>(fonts_[1]),TRUE);
        for(int id:{12,16,18}) SendMessageW(Item(id),WM_SETFONT,reinterpret_cast<WPARAM>(fonts_[2]),TRUE);
        InvalidateRect(window_,nullptr,TRUE);
    }
    std::wstring Value(int id) {
        int n=GetWindowTextLengthW(Item(id)); std::wstring text(n+1,L'\0'); GetWindowTextW(Item(id),text.data(),n+1); text.resize(n); return text;
    }
    void StatusText(const std::wstring& s) { SetWindowTextW(Item(Status),s.c_str()); }
    std::wstring ConnectionUri() { return L"lanbridge://"+Value(Ip)+L":42831/"+bridge::Wide(code_); }
    void UpdateQr() {
        try {
            auto address=Value(Ip);
            if(address.empty() || address.find(L"地址")!=std::wstring::npos) { qr_.reset(); }
            else qr_=qrcodegen::QrCode::encodeText(bridge::Utf8(ConnectionUri()).c_str(),qrcodegen::QrCode::Ecc::MEDIUM);
        } catch(...) { qr_.reset(); }
        InvalidateRect(Item(Qr),nullptr,TRUE);
    }
    void DrawQr(const DRAWITEMSTRUCT& item) {
        FillRect(item.hDC,&item.rcItem,static_cast<HBRUSH>(GetStockObject(WHITE_BRUSH)));
        if(!qr_) {
            int saved=SaveDC(item.hDC); SelectObject(item.hDC,fonts_[0]); SetBkMode(item.hDC,TRANSPARENT); SetTextColor(item.hDC,RGB(36,47,65));
            DrawTextW(item.hDC,L"请连接局域网并选择电脑地址",-1,const_cast<RECT*>(&item.rcItem),DT_CENTER|DT_VCENTER|DT_WORDBREAK); RestoreDC(item.hDC,saved); return;
        }
        int n=qr_->getSize(),cell=std::max(1,static_cast<int>(std::min(item.rcItem.right-item.rcItem.left,item.rcItem.bottom-item.rcItem.top))/(n+8));
        int side=(n+8)*cell;
        int left=item.rcItem.left+(item.rcItem.right-item.rcItem.left-side)/2+4*cell;
        int top=item.rcItem.top+(item.rcItem.bottom-item.rcItem.top-side)/2+4*cell;
        for(int y=0;y<n;y++) for(int x=0;x<n;x++) if(qr_->getModule(x,y)) {
            RECT module{left+x*cell,top+y*cell,left+(x+1)*cell,top+(y+1)*cell};
            FillRect(item.hDC,&module,static_cast<HBRUSH>(GetStockObject(BLACK_BRUSH)));
        }
    }
    template<class Work> void Send(Work work) {
        if(sending_.exchange(true)) { StatusText(L"当前发送尚未结束，请稍候"); return; }
        if(!server_->IsConnected()) { sending_=false; StatusText(L"请先在 Android 端连接电脑"); return; }
        if(sender_.joinable()) sender_.join();
        for(int id:{SendText,SendClipboard,SendFile}) EnableWindow(Item(id),FALSE);
        sender_=std::thread([this,work] {
            try { work(); } catch(const std::exception& e) { Queue({bridge::Event::Error,bridge::Wide(e.what())}); }
            sending_=false; Queue({bridge::Event::Info,L""});
        });
    }
    void Files(const std::vector<std::filesystem::path>& paths) {
        if(paths.empty()) return;
        Send([this,paths] { server_->SendFiles(paths); });
    }
    bool AcceptFiles(shelltransfer::Paths paths) {
        size_t count = paths.size(); for(const auto& batch:pendingFiles_) count += batch.size();
        if(count > 4096) { StatusText(L"待发送文件过多，请等待当前队列完成"); return false; }
        pendingFiles_.push_back(std::move(paths));
        if(!server_) return true;
        if(!server_->IsConnected()) {
            StatusText(L"右键分享已排队 · 请用手机扫描二维码，连接后自动发送");
            ShowWindow(window_,SW_RESTORE); SetForegroundWindow(window_);
        } else if(sending_) StatusText(L"右键分享已排队 · 当前传输完成后自动发送");
        PumpFiles(); return true;
    }
    void PumpFiles() {
        if(sending_ || pendingFiles_.empty() || !server_ || !server_->IsConnected()) return;
        auto paths = std::move(pendingFiles_.front()); pendingFiles_.pop_front(); Files(paths);
    }
    void Integration(bool enable) {
        if(integrating_.exchange(true)) { StatusText(L"正在更新右键菜单，请稍候"); return; }
        if(integration_.joinable()) integration_.join();
        StatusText(enable?L"正在启用 Windows 11 右键发送…":L"正在移除右键发送…");
        for(int id:{EnableMenu,DisableMenu}) EnableWindow(Item(id),FALSE);
        integration_ = std::thread([this,enable] {
            std::filesystem::path log;
            try {
                std::wstring module(32768,L'\0'); auto n=GetModuleFileNameW(nullptr,module.data(),static_cast<DWORD>(module.size()));
                if(!n || n >= module.size()) throw std::runtime_error("Cannot find program directory"); module.resize(n);
                auto script=std::filesystem::path(module).parent_path()/L"scripts"/L"register-context-menu.ps1";
                // Keep the previously delivered desktop EXE usable beside its
                // complete LanBridge project folder.
                if(!std::filesystem::is_regular_file(script)) script=std::filesystem::path(module).parent_path()/L"LanBridge"/L"scripts"/L"register-context-menu.ps1";
                if(!std::filesystem::is_regular_file(script)) throw std::runtime_error("请将便携包完整解压后再启用右键菜单");
                wchar_t system[MAX_PATH]{},temporary[MAX_PATH]{};
                GetSystemDirectoryW(system,MAX_PATH); GetTempPathW(MAX_PATH,temporary);
                GUID guid{}; CoCreateGuid(&guid); wchar_t token[40]{}; StringFromGUID2(guid,token,40);
                log=std::filesystem::path(temporary)/(L"LanBridge-menu-"+std::wstring(token)+L".log");
                auto powershell=std::filesystem::path(system)/L"WindowsPowerShell"/L"v1.0"/L"powershell.exe";
                auto command=shelltransfer::QuoteArgument(powershell.wstring())+L" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "+shelltransfer::QuoteArgument(script.wstring())+
                    L" -Action "+(enable?std::wstring(L"Enable"):std::wstring(L"Disable"))+L" -LogPath "+shelltransfer::QuoteArgument(log.wstring());
                STARTUPINFOW startup{sizeof(startup)}; PROCESS_INFORMATION process{};
                if(!CreateProcessW(powershell.c_str(),command.data(),nullptr,nullptr,FALSE,CREATE_NO_WINDOW,nullptr,nullptr,&startup,&process)) throw std::runtime_error("Cannot start menu registration");
                CloseHandle(process.hThread); WaitForSingleObject(process.hProcess,INFINITE);
                DWORD exitCode=1; GetExitCodeProcess(process.hProcess,&exitCode); CloseHandle(process.hProcess);
                std::ifstream input(log,std::ios::binary); std::string output((std::istreambuf_iterator<char>(input)),{});
                if(output.starts_with("\xEF\xBB\xBF")) output.erase(0,3);
                Queue({exitCode?bridge::Event::Error:bridge::Event::Info,output.empty()?(exitCode?L"右键菜单更新失败，请查看使用说明":L"右键菜单已更新"):bridge::Wide(output)});
            } catch(const std::exception& e) { Queue({bridge::Event::Error,bridge::Wide(e.what())}); }
            if(!log.empty()) DeleteFileW(log.c_str());
            integrating_=false; Queue({bridge::Event::Info,L""});
        });
    }
    void SelectFiles() {
        std::vector<wchar_t> buffer(65536);
        OPENFILENAMEW f{sizeof(f)}; f.hwndOwner=window_; f.lpstrFilter=L"所有文件\0*.*\0\0"; f.lpstrFile=buffer.data(); f.nMaxFile=static_cast<DWORD>(buffer.size()); f.Flags=OFN_EXPLORER|OFN_ALLOWMULTISELECT|OFN_FILEMUSTEXIST|OFN_NOCHANGEDIR;
        if(!GetOpenFileNameW(&f)) return;
        std::vector<std::filesystem::path> paths; std::filesystem::path first(buffer.data());
        auto p=buffer.data()+wcslen(buffer.data())+1;
        if(!*p) paths.push_back(first); else while(*p) { paths.push_back(first/p); p+=wcslen(p)+1; }
        Files(paths);
    }
    void RecordReceived(Record record) {
        auto label=record.file ? L"文件  ·  "+record.path.filename().wstring() : L"文本  ·  "+record.text.substr(0,100);
        for(auto& c:label) if(c==L'\n' || c==L'\r' || c==L'\t') c=L' ';
        records_.push_front(std::move(record)); SendMessageW(Item(History),LB_INSERTSTRING,0,reinterpret_cast<LPARAM>(label.c_str()));
        while(records_.size()>80) { records_.pop_back(); SendMessageW(Item(History),LB_DELETESTRING,80,0); }
        SendMessageW(Item(History),LB_SETCURSEL,0,0);
    }
    void Drain() {
        std::deque<bridge::Event> queue; { std::lock_guard lock(eventMutex_); queue.swap(events_); }
        for(auto& e:queue) {
            switch(e.kind) {
            case bridge::Event::Text: {
                bool ok=bridge::CopyText(window_,e.message); RecordReceived({false,e.message,{}});
                StatusText(ok?L"收到文本 · 已自动复制到剪贴板":L"收到文本 · 剪贴板暂时被占用，请点重新复制");
                server_->Acknowledge(ok?L"Windows 已接收文本并复制到剪贴板":L"Windows 已接收文本，剪贴板占用"); break;
            }
            case bridge::Event::File: {
                bool ok=bridge::CopyFiles(window_,{e.path}); RecordReceived({true,L"",e.path});
                StatusText(ok?L"收到 "+e.message+L" · 已复制到文件剪贴板":L"文件已保存 · 剪贴板暂时被占用，请点重新复制");
                server_->Acknowledge(ok?L"Windows 已保存并复制文件："+e.message:L"Windows 已保存文件，剪贴板占用"); break;
            }
            case bridge::Event::Progress: {
                int n=e.total ? static_cast<int>(e.current*100/e.total) : 100;
                SendMessageW(Item(Progress),PBM_SETPOS,n,0);
                StatusText(e.message+L" · "+std::to_wstring(n)+L"%  ("+std::to_wstring(e.current/1024)+L" / "+std::to_wstring(e.total/1024)+L" KiB)"); break;
            }
            default: if(!e.message.empty()) StatusText(e.message); break;
            }
        }
        for(int id:{SendText,SendClipboard,SendFile}) EnableWindow(Item(id),!sending_);
        for(int id:{EnableMenu,DisableMenu}) EnableWindow(Item(id),!integrating_);
        PumpFiles();
    }
    void Command(int id) {
        switch(id) {
        case CopyLink: {
            auto uri=ConnectionUri();
            StatusText(bridge::CopyText(window_,uri)?L"连接信息已复制，可粘贴到 Android 配对页":L"剪贴板被占用，请重试"); break;
        }
        case Disconnect: server_->Disconnect(); break;
        case EnableMenu: Integration(true); break;
        case DisableMenu: Integration(false); break;
        case Startup: {
            bool enabled=SendMessageW(Item(Startup),BM_GETCHECK,0,0)==BST_CHECKED;
            try {
                startup::SetEnabled(enabled,startup::ExecutablePath()); startupEnabled_=enabled;
                StatusText(enabled?L"已开启开机启动 · 登录 Windows 后默认收至托盘":L"已关闭开机启动");
            } catch(const std::exception& e) {
                SendMessageW(Item(Startup),BM_SETCHECK,startupEnabled_?BST_CHECKED:BST_UNCHECKED,0);
                StatusText(bridge::Wide(e.what()));
            }
            break;
        }
        case SendText: { auto text=Value(Text); Send([this,text] { server_->SendText(text); }); break; }
        case SendClipboard: {
            auto files=bridge::ReadFiles(window_);
            if(!files.empty()) Files(files); else { auto text=bridge::ReadText(window_); Send([this,text] { server_->SendText(text); }); } break;
        }
        case SendFile: SelectFiles(); break;
        case OpenFolder: ShellExecuteW(window_,L"open",folder_.c_str(),nullptr,nullptr,SW_SHOWNORMAL); break;
        case Clear: records_.clear(); SendMessageW(Item(History),LB_RESETCONTENT,0,0); break;
        case Recopy: {
            int i=static_cast<int>(SendMessageW(Item(History),LB_GETCURSEL,0,0));
            if(i>=0 && static_cast<size_t>(i)<records_.size()) {
                const auto& r=records_[i]; bool ok=r.file?bridge::CopyFiles(window_,{r.path}):bridge::CopyText(window_,r.text);
                StatusText(ok?L"已重新复制到剪贴板":L"复制失败，请重试");
            } break;
        }
        case Exit: exiting_=true; DestroyWindow(window_); break;
        }
    }
    void Paint(HDC dc) {
        RECT rc; GetClientRect(window_,&rc); FillRect(dc,&rc,background_);
        auto oldPen=SelectObject(dc,GetStockObject(NULL_PEN)); auto oldBrush=SelectObject(dc,card_);
        RoundRect(dc,S(24),S(98+ui::TitleBar::kHeight),S(302),rc.bottom-S(63),S(18),S(18));
        RoundRect(dc,S(316),S(98+ui::TitleBar::kHeight),rc.right-S(24),S(331+ui::TitleBar::kHeight),S(18),S(18));
        RoundRect(dc,S(316),S(341+ui::TitleBar::kHeight),rc.right-S(24),rc.bottom-S(63),S(18),S(18));
        SelectObject(dc,oldPen); SelectObject(dc,oldBrush);
        titleBar_.Paint(dc);
    }
    static LRESULT CALLBACK Proc(HWND h,UINT m,WPARAM w,LPARAM l) {
        auto* app=reinterpret_cast<App*>(GetWindowLongPtrW(h,GWLP_USERDATA));
        if(m==WM_NCCREATE) { app=static_cast<App*>(reinterpret_cast<CREATESTRUCTW*>(l)->lpCreateParams); app->window_=h; SetWindowLongPtrW(h,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(app)); }
        if(!app) return DefWindowProcW(h,m,w,l);
        if(TaskbarCreatedMessage && m==TaskbarCreatedMessage) {
            app->popup_.HideTooltip(); Shell_NotifyIconW(NIM_ADD,&app->tray_);
            app->tray_.uVersion=NOTIFYICON_VERSION_4; app->trayVersion4_=Shell_NotifyIconW(NIM_SETVERSION,&app->tray_)!=FALSE; return 0;
        }
        switch(m) {
        case WM_NCCALCSIZE: return ui::TitleBar::CalculateClient(h,w,l);
        case WM_NCHITTEST: return ui::TitleBar::HitTest(h,l);
        case WM_ACTIVATE: InvalidateRect(h,nullptr,FALSE); break;
        case WM_SETTEXT: { auto result=DefWindowProcW(h,m,w,l); InvalidateRect(h,nullptr,FALSE); return result; }
        case WM_CREATE: app->Create(); return 0;
        case WM_SIZE: if(w!=SIZE_MINIMIZED && !app->fonts_.empty()) app->Layout(); return 0;
        case WM_GETMINMAXINFO: { auto p=reinterpret_cast<MINMAXINFO*>(l); p->ptMinTrackSize={app->S(990),app->S(720)}; return 0; }
        case WM_DPICHANGED: {
            app->dpi_=HIWORD(w); app->FontSetup(); auto r=reinterpret_cast<RECT*>(l); SetWindowPos(h,nullptr,r->left,r->top,r->right-r->left,r->bottom-r->top,SWP_NOZORDER|SWP_NOACTIVATE); app->Layout(); return 0;
        }
        case WM_COMMAND: if(app->titleBar_.HandleCommand(LOWORD(w))) return 0; if(LOWORD(w)==Ip && HIWORD(w)==CBN_SELCHANGE) app->UpdateQr(); else if(LOWORD(w)==History && HIWORD(w)==LBN_DBLCLK) app->Command(Recopy); else if(HIWORD(w)==BN_CLICKED) app->Command(LOWORD(w)); return 0;
        case WM_MEASUREITEM: if(app->popup_.Measure(*reinterpret_cast<MEASUREITEMSTRUCT*>(l))) return TRUE; break;
        case WM_DRAWITEM: {
            auto item=reinterpret_cast<DRAWITEMSTRUCT*>(l);
            if(app->titleBar_.DrawButton(*item) || app->popup_.Draw(*item)) return TRUE;
            if(item->CtlID==Qr) { app->DrawQr(*item); return TRUE; } break;
        }
        case EventsMessage: app->Drain(); return 0;
        case WM_COPYDATA: {
            auto* data=reinterpret_cast<COPYDATASTRUCT*>(l);
            if(!data || data->dwData != shelltransfer::MessageId) return FALSE;
            try { return app->AcceptFiles(shelltransfer::Decode(data->lpData,data->cbData))?TRUE:FALSE; }
            catch(...) { return FALSE; }
        }
        case WM_DROPFILES: {
            auto drop=reinterpret_cast<HDROP>(w); auto n=DragQueryFileW(drop,0xffffffff,nullptr,0); std::vector<std::filesystem::path> paths;
            for(UINT i=0;i<n;i++) { std::wstring p(DragQueryFileW(drop,i,nullptr,0)+1,L'\0'); DragQueryFileW(drop,i,p.data(),static_cast<UINT>(p.size())); p.pop_back(); if(std::filesystem::is_regular_file(p)) paths.emplace_back(p); }
            DragFinish(drop); app->Files(paths); return 0;
        }
        case TrayMessage: {
            UINT event=app->trayVersion4_?LOWORD(l):static_cast<UINT>(l);
            POINT p; if(app->trayVersion4_) p={GET_X_LPARAM(w),GET_Y_LPARAM(w)}; else GetCursorPos(&p);
            if(event==NIN_POPUPOPEN) app->popup_.ShowTooltip(h,p);
            if(event==NIN_POPUPCLOSE) app->popup_.HideTooltip();
            if(event==WM_LBUTTONDBLCLK || event==WM_LBUTTONUP || event==NIN_SELECT || event==NIN_KEYSELECT) { app->popup_.HideTooltip(); ShowWindow(h,SW_RESTORE); SetForegroundWindow(h); }
            if(event==WM_RBUTTONUP || event==WM_CONTEXTMENU) {
                if(p.x==-1 && p.y==-1) {
                    NOTIFYICONIDENTIFIER icon{sizeof(icon)}; icon.hWnd=h; icon.uID=1; RECT rect{};
                    if(SUCCEEDED(Shell_NotifyIconGetRect(&icon,&rect))) p={rect.left,rect.top}; else GetCursorPos(&p);
                }
                auto menu=CreatePopupMenu(); AppendMenuW(menu,MF_STRING,1,L"显示 LanBridge");
                AppendMenuW(menu,MF_SEPARATOR,0,nullptr);
                AppendMenuW(menu,MF_STRING|(app->integrating_?MF_GRAYED:0),EnableMenu,L"启用右键发送");
                AppendMenuW(menu,MF_STRING|(app->integrating_?MF_GRAYED:0),DisableMenu,L"移除右键发送");
                AppendMenuW(menu,MF_STRING|(app->startupEnabled_?MF_CHECKED:0),Startup,L"开机启动（收至托盘）");
                AppendMenuW(menu,MF_SEPARATOR,0,nullptr); AppendMenuW(menu,MF_STRING,Exit,L"退出");
                auto id=app->popup_.Track(menu,h,p); DestroyMenu(menu);
                if(id==1) { ShowWindow(h,SW_RESTORE); SetForegroundWindow(h); }
                else if(id) {
                    if(id==Startup) SendMessageW(app->Item(Startup),BM_SETCHECK,app->startupEnabled_?BST_UNCHECKED:BST_CHECKED,0);
                    app->Command(id);
                }
            } return 0;
        }
        case WM_CLOSE: ShowWindow(h,SW_HIDE); app->StatusText(L"已收起到托盘，继续等待接收"); return 0;
        case WM_QUERYENDSESSION: return TRUE;
        case WM_ENDSESSION: if(w) DestroyWindow(h); return 0;
        case WM_CTLCOLORSTATIC: {
            auto dc=reinterpret_cast<HDC>(w); SetTextColor(dc,RGB(36,47,65)); SetBkMode(dc,TRANSPARENT);
            int id=GetDlgCtrlID(reinterpret_cast<HWND>(l)); return reinterpret_cast<LRESULT>(id==10 || id==11 || id==Status?app->background_:app->card_);
        }
        case WM_CTLCOLORBTN: {
            if(GetDlgCtrlID(reinterpret_cast<HWND>(l))!=Startup) break;
            SetTextColor(reinterpret_cast<HDC>(w),RGB(36,47,65)); SetBkMode(reinterpret_cast<HDC>(w),TRANSPARENT);
            return reinterpret_cast<LRESULT>(app->background_);
        }
        case WM_ERASEBKGND: return 1;
        case WM_PRINTCLIENT: app->Paint(reinterpret_cast<HDC>(w)); return 0;
        case WM_PAINT: {
            PAINTSTRUCT ps; auto dc=BeginPaint(h,&ps); app->Paint(dc); EndPaint(h,&ps); return 0;
        }
        case WM_DESTROY: if(app->server_) app->server_->Stop(); if(app->sender_.joinable()) app->sender_.join(); Shell_NotifyIconW(NIM_DELETE,&app->tray_); PostQuitMessage(0); return 0;
        }
        return DefWindowProcW(h,m,w,l);
    }
};
}
int WINAPI wWinMain(HINSTANCE instance,HINSTANCE,PWSTR,int show) {
    shelltransfer::Paths initial;
    bool startupLaunch=false;
    try {
        int argc=0; auto argv=CommandLineToArgvW(GetCommandLineW(),&argc);
        if(!argv) return 1;
        startupLaunch=argc==2 && CompareStringOrdinal(argv[1],-1,L"--startup",-1,TRUE)==CSTR_EQUAL;
        bool valid=argc==1 || startupLaunch || (argc==3 && wcscmp(argv[1],L"--send-request")==0);
        std::filesystem::path request=argc==3?argv[2]:L""; LocalFree(argv);
        if(!valid) throw std::runtime_error("Invalid command line");
        if(!request.empty()) initial=shelltransfer::ReadRequest(request);
    } catch(const std::exception& e) { MessageBoxW(nullptr,bridge::Wide(e.what()).c_str(),L"LanBridge",MB_ICONERROR); return 1; }
    auto mutex=CreateMutexW(nullptr,TRUE,L"Local\\LanBridge.Singleton");
    if(GetLastError()==ERROR_ALREADY_EXISTS) {
        HWND h=nullptr; for(int i=0;i<100 && !h;i++) { h=FindWindowW(L"LanBridgeWindow",nullptr); if(!h) Sleep(50); }
        bool accepted=true;
        if(!initial.empty()) {
            try { accepted=h && shelltransfer::Forward(h,initial); } catch(...) { accepted=false; }
            if(!accepted) MessageBoxW(nullptr,L"无法将文件交给正在运行的 LanBridge。请确认运行的是新版程序，或退出旧版后重试。",L"LanBridge",MB_ICONERROR);
        } else if(h && !startupLaunch) { ShowWindow(h,SW_RESTORE); SetForegroundWindow(h); }
        CloseHandle(mutex); return accepted?0:1;
    }
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED); INITCOMMONCONTROLSEX controls{sizeof(controls),ICC_STANDARD_CLASSES|ICC_PROGRESS_CLASS}; InitCommonControlsEx(&controls);
    int result=1;
    try { App app; result=app.Run(instance,startupLaunch?SW_HIDE:show,initial); } catch(const std::exception& e) { MessageBoxW(nullptr,bridge::Wide(e.what()).c_str(),L"LanBridge",MB_ICONERROR); }
    CoUninitialize(); if(mutex) CloseHandle(mutex); return result;
}
