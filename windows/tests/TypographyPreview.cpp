#include "ui/UiTypography.h"
#include <objbase.h>
#include <ole2.h>
#include <gdiplus.h>
#include <shellapi.h>
#include <commctrl.h>
#include <iostream>
#include <stdexcept>
#include <vector>

void Require(bool condition,const char* message) { if(!condition) throw std::runtime_error(message); }
struct Process {
    PROCESS_INFORMATION info{}; HWND window=nullptr;
    ~Process() {
        if(window) { PostMessageW(window,WM_CANCELMODE,0,0); SendMessageTimeoutW(window,WM_COMMAND,900,0,SMTO_ABORTIFHUNG,3000,nullptr); }
        if(info.hProcess) { WaitForSingleObject(info.hProcess,10000); CloseHandle(info.hThread); CloseHandle(info.hProcess); }
    }
};
HWND menuWindow=nullptr;
BOOL CALLBACK FindMenu(HWND window,LPARAM) {
    wchar_t name[64]{}; GetClassNameW(window,name,64);
    if(std::wstring(name)==L"#32768" && IsWindowVisible(window)) menuWindow=window;
    return TRUE;
}
void Snapshot(HWND window,const wchar_t* path) {
    RECT rect{}; GetWindowRect(window,&rect); HDC source=GetDC(window),dc=CreateCompatibleDC(source);
    std::cout<<"Popup bounds: "<<rect.left<<","<<rect.top<<" "<<rect.right-rect.left<<"x"<<rect.bottom-rect.top<<std::endl;
    Require(rect.right>rect.left && rect.bottom>rect.top,"Visible menu bounds");
    HBITMAP bitmap=CreateCompatibleBitmap(source,rect.right-rect.left,rect.bottom-rect.top); auto old=SelectObject(dc,bitmap);
    Require(PrintWindow(window,dc,0)!=FALSE,"Print popup menu"); SelectObject(dc,old);
    UINT count=0,size=0; Gdiplus::GetImageEncodersSize(&count,&size); std::vector<BYTE> storage(size);
    auto codecs=reinterpret_cast<Gdiplus::ImageCodecInfo*>(storage.data()); Gdiplus::GetImageEncoders(count,size,codecs);
    { Gdiplus::Bitmap image(bitmap,nullptr); for(UINT i=0;i<count;i++) if(std::wstring(codecs[i].MimeType)==L"image/png") Require(image.Save(path,&codecs[i].Clsid,nullptr)==Gdiplus::Ok,"Save menu PNG"); }
    DeleteObject(bitmap); DeleteDC(dc); ReleaseDC(window,source);
}
int wmain(int argc,wchar_t** argv) {
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    INITCOMMONCONTROLSEX controls{sizeof(controls),ICC_STANDARD_CLASSES}; InitCommonControlsEx(&controls);
    ULONG_PTR token=0; Gdiplus::GdiplusStartupInput input; Gdiplus::GdiplusStartup(&token,&input,nullptr);
    try {
        // Capture either application's real tray popup without relaunching it.
        if(argc==5 && std::wstring(argv[1])==L"--existing") {
            Require(std::wstring(argv[2])==L"LanBridgeWindow" || std::wstring(argv[2])==L"NativeScreenshot.MainWindow","Supported application class");
            auto window=FindWindowW(argv[2],argv[3]); Require(window!=nullptr,"Existing application window");
            auto thread=GetWindowThreadProcessId(window,nullptr);
            RECT area{}; SystemParametersInfoW(SPI_GETWORKAREA,0,&area,0);
            POINT anchor{area.left+(area.right-area.left)/2,area.top+(area.bottom-area.top)/2};
            if(std::wstring(argv[2])==L"LanBridgeWindow") PostMessageW(window,WM_APP+2,MAKELPARAM(anchor.x,anchor.y),MAKELPARAM(WM_CONTEXTMENU,1));
            else PostMessageW(window,WM_CONTEXTMENU,0,MAKELPARAM(anchor.x,anchor.y));
            for(int i=0;i<100 && !menuWindow;i++) { EnumThreadWindows(thread,FindMenu,0); if(!menuWindow) Sleep(50); }
            if(!menuWindow) { PostMessageW(window,WM_CANCELMODE,0,0); throw std::runtime_error("Existing tray popup"); }
            try { Sleep(150); Snapshot(menuWindow,argv[4]); }
            catch(...) { PostMessageW(window,WM_CANCELMODE,0,0); throw; }
            PostMessageW(window,WM_CANCELMODE,0,0);
            std::cout<<"PASS: captured existing tray popup; application and connection remain running"<<std::endl;
            Gdiplus::GdiplusShutdown(token); return 0;
        }
        Require(argc==3 && !FindWindowW(L"LanBridgeWindow",nullptr),"Expected app and PNG paths, no active LanBridge");
        for(UINT dpi:{96u,144u,192u}) {
            auto font=ui::CreateUiFont(dpi); LOGFONTW value{};
            Require(font && GetObjectW(font,sizeof(value),&value)==sizeof(value) && std::wstring(value.lfFaceName)==L"Microsoft YaHei UI" && value.lfHeight==-MulDiv(14,dpi,96),"Unified scalable font"); DeleteObject(font);
        }
        Process app; std::wstring command=L"\""+std::wstring(argv[1])+L"\""; STARTUPINFOW startup{sizeof(startup)};
        Require(CreateProcessW(argv[1],command.data(),nullptr,nullptr,FALSE,0,nullptr,nullptr,&startup,&app.info)!=FALSE,"Launch app"); WaitForInputIdle(app.info.hProcess,10000);
        for(int i=0;i<100 && !app.window;i++) { app.window=FindWindowW(L"LanBridgeWindow",nullptr); if(!app.window) Sleep(50); }
        Require(app.window!=nullptr,"Main window");
        RECT area{}; SystemParametersInfoW(SPI_GETWORKAREA,0,&area,0);
        POINT anchor{area.left+(area.right-area.left)/2,area.top+(area.bottom-area.top)/2};
        PostMessageW(app.window,WM_APP+2,MAKELPARAM(anchor.x,anchor.y),MAKELPARAM(WM_CONTEXTMENU,1));
        for(int i=0;i<100 && !menuWindow;i++) { EnumThreadWindows(app.info.dwThreadId,FindMenu,0); if(!menuWindow) Sleep(50); }
        Require(menuWindow!=nullptr,"Actual tray popup"); Sleep(150); Snapshot(menuWindow,argv[2]);
        PostMessageW(app.window,WM_CANCELMODE,0,0); Sleep(150);
        std::cout<<"PASS: unified font at 100/150/200 percent; actual Windows tray popup captured; clipboard and startup setting untouched"<<std::endl;
        Gdiplus::GdiplusShutdown(token); return 0;
    } catch(const std::exception& error) { std::cerr<<"FAIL: "<<error.what()<<std::endl; Gdiplus::GdiplusShutdown(token); return 1; }
}
