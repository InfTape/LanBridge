#include "ui/PopupMenu.h"
#include "ui/UiTypography.h"
#include <commctrl.h>
#include <shellapi.h>
#include <algorithm>

namespace ui {
PopupMenu::~PopupMenu() {
    if(tooltip_ && IsWindow(tooltip_)) DestroyWindow(tooltip_);
    if(font_) DeleteObject(font_);
}
bool PopupMenu::Prepare(HWND owner,const POINT& anchor) {
    owner_=owner;
    if(!tooltip_) {
        tooltip_=CreateWindowExW(WS_EX_TOPMOST|WS_EX_NOACTIVATE|WS_EX_TOOLWINDOW,
            TOOLTIPS_CLASSW,nullptr,WS_POPUP|TTS_ALWAYSTIP|TTS_NOPREFIX,
            anchor.x,anchor.y,0,0,owner,nullptr,GetModuleHandleW(nullptr),nullptr);
        if(!tooltip_) return false;
        TOOLINFOW tool{}; tool.cbSize=sizeof(tool); tool.uFlags=TTF_TRACK|TTF_ABSOLUTE; tool.hwnd=owner; tool.uId=1;
        tool.lpszText=const_cast<wchar_t*>(L"LanBridge · 局域网互传");
        if(!SendMessageW(tooltip_,TTM_ADDTOOLW,0,reinterpret_cast<LPARAM>(&tool))) {
            DestroyWindow(tooltip_); tooltip_=nullptr; return false;
        }
    }
    SetWindowPos(tooltip_,HWND_TOPMOST,anchor.x,anchor.y,0,0,SWP_NOSIZE|SWP_NOACTIVATE);
    const UINT dpi=UiDpi(tooltip_);
    if(!font_ || dpi!=dpi_) {
        // native-screenshot-tool uses 9 pt (12 logical pixels), not the body UI size.
        const HFONT replacement=CreateUiFont(dpi); if(!replacement) return false;
        const auto previous=font_; font_=replacement; dpi_=dpi;
        SendMessageW(tooltip_,WM_SETFONT,reinterpret_cast<WPARAM>(font_),TRUE);
        if(previous) DeleteObject(previous);
    }
    return true;
}
UINT PopupMenu::Track(HMENU menu,HWND owner,const POINT& anchor) {
    HideTooltip(); if(!Prepare(owner,anchor)) return 0;
    const int count=GetMenuItemCount(menu); if(count<0) return 0;
    labels_.clear(); labels_.resize(count);
    for(int i=0;i<count;i++) {
        MENUITEMINFOW item{}; item.cbSize=sizeof(item); item.fMask=MIIM_FTYPE|MIIM_STRING;
        if(!GetMenuItemInfoW(menu,i,TRUE,&item) || (item.fType&MFT_SEPARATOR)) continue;
        auto& label=labels_[i]; label.resize(item.cch+1); item.dwTypeData=label.data(); item.cch=static_cast<UINT>(label.size());
        if(!GetMenuItemInfoW(menu,i,TRUE,&item)) continue;
        label.resize(wcslen(label.c_str()));
        item.fMask=MIIM_FTYPE|MIIM_DATA|MIIM_STRING; item.fType|=MFT_OWNERDRAW;
        item.dwItemData=reinterpret_cast<ULONG_PTR>(label.c_str()); item.dwTypeData=label.data();
        SetMenuItemInfoW(menu,i,TRUE,&item);
    }
    SetForegroundWindow(owner); open_=true;
    const UINT command=TrackPopupMenu(menu,TPM_RIGHTBUTTON|TPM_RETURNCMD,anchor.x,anchor.y,0,owner,nullptr);
    open_=false; PostMessageW(owner,WM_NULL,0,0); return command;
}
bool PopupMenu::Measure(MEASUREITEMSTRUCT& item) const {
    if(item.CtlType!=ODT_MENU || !item.itemData || !font_) return false;
    const auto text=reinterpret_cast<const wchar_t*>(item.itemData); const HDC dc=GetDC(owner_); if(!dc) return false;
    const auto previous=SelectObject(dc,font_); SIZE size{}; GetTextExtentPoint32W(dc,text,static_cast<int>(wcslen(text)),&size);
    SelectObject(dc,previous); ReleaseDC(owner_,dc);
    // Match the reference tray's normal 124-DIP content width, expanding for longer text.
    item.itemWidth=std::max(static_cast<int>(size.cx)+ScaleForDpi(40,dpi_,96),ScaleForDpi(124,dpi_,96));
    item.itemHeight=std::max(static_cast<int>(size.cy)+ScaleForDpi(10,dpi_,96),ScaleForDpi(32,dpi_,96)); return true;
}
bool PopupMenu::Draw(const DRAWITEMSTRUCT& item) const {
    if(item.CtlType!=ODT_MENU || !item.itemData || !font_) return false;
    const int saved=SaveDC(item.hDC);
    const bool selected=(item.itemState&ODS_SELECTED)!=0,disabled=(item.itemState&(ODS_DISABLED|ODS_GRAYED))!=0;
    FillRect(item.hDC,&item.rcItem,GetSysColorBrush(selected?COLOR_HIGHLIGHT:COLOR_MENU));
    const COLORREF color=GetSysColor(disabled?COLOR_GRAYTEXT:selected?COLOR_HIGHLIGHTTEXT:COLOR_MENUTEXT);
    SelectObject(item.hDC,font_); SetBkMode(item.hDC,TRANSPARENT); SetTextColor(item.hDC,color);
    RECT text=item.rcItem; text.left+=ScaleForDpi(18,dpi_,96); text.right-=ScaleForDpi(12,dpi_,96);
    DrawTextW(item.hDC,reinterpret_cast<const wchar_t*>(item.itemData),-1,&text,DT_SINGLELINE|DT_VCENTER|DT_NOPREFIX);
    if(item.itemState&ODS_CHECKED) {
        const int x=item.rcItem.left+ScaleForDpi(4,dpi_,96),y=(item.rcItem.top+item.rcItem.bottom)/2;
        const HPEN pen=CreatePen(PS_SOLID,std::max(1,ScaleForDpi(2,dpi_,96)),color); const auto old=SelectObject(item.hDC,pen);
        MoveToEx(item.hDC,x,y,nullptr); LineTo(item.hDC,x+ScaleForDpi(3,dpi_,96),y+ScaleForDpi(3,dpi_,96)); LineTo(item.hDC,x+ScaleForDpi(8,dpi_,96),y-ScaleForDpi(3,dpi_,96));
        SelectObject(item.hDC,old); DeleteObject(pen);
    }
    RestoreDC(item.hDC,saved); return true;
}
void PopupMenu::ShowTooltip(HWND owner,const POINT& anchor) {
    if(open_ || !Prepare(owner,anchor)) return;
    NOTIFYICONIDENTIFIER icon{}; icon.cbSize=sizeof(icon); icon.hWnd=owner; icon.uID=1; RECT iconRect{};
    if(FAILED(Shell_NotifyIconGetRect(&icon,&iconRect))) iconRect={anchor.x,anchor.y,anchor.x,anchor.y};
    const int padding=ScaleForDpi(8,dpi_,96); RECT margin{padding,padding/2,padding,padding/2};
    SendMessageW(tooltip_,TTM_SETMARGIN,0,reinterpret_cast<LPARAM>(&margin));
    const HDC dc=GetDC(tooltip_); const auto old=SelectObject(dc,font_); SIZE size{}; constexpr wchar_t tip[]=L"LanBridge · 局域网互传";
    GetTextExtentPoint32W(dc,tip,static_cast<int>(std::size(tip)-1),&size); SelectObject(dc,old); ReleaseDC(tooltip_,dc);
    const int width=size.cx+2*padding+ScaleForDpi(6,dpi_,96),height=size.cy+padding+ScaleForDpi(6,dpi_,96);
    MONITORINFO monitor{}; monitor.cbSize=sizeof(monitor); GetMonitorInfoW(MonitorFromPoint(anchor,MONITOR_DEFAULTTONEAREST),&monitor);
    const int x=std::clamp(static_cast<int>(iconRect.left),static_cast<int>(monitor.rcWork.left),std::max(static_cast<int>(monitor.rcWork.left),static_cast<int>(monitor.rcWork.right)-width));
    int y=iconRect.top-height-ScaleForDpi(4,dpi_,96); if(y<monitor.rcWork.top) y=iconRect.bottom+ScaleForDpi(4,dpi_,96);
    y=std::clamp(y,static_cast<int>(monitor.rcWork.top),std::max(static_cast<int>(monitor.rcWork.top),static_cast<int>(monitor.rcWork.bottom)-height));
    TOOLINFOW tool{}; tool.cbSize=sizeof(tool); tool.hwnd=owner; tool.uId=1;
    SendMessageW(tooltip_,TTM_TRACKPOSITION,0,MAKELPARAM(x,y)); SendMessageW(tooltip_,TTM_TRACKACTIVATE,TRUE,reinterpret_cast<LPARAM>(&tool));
}
void PopupMenu::HideTooltip() {
    if(!tooltip_ || !IsWindow(tooltip_)) return;
    TOOLINFOW tool{}; tool.cbSize=sizeof(tool); tool.hwnd=owner_; tool.uId=1;
    SendMessageW(tooltip_,TTM_TRACKACTIVATE,FALSE,reinterpret_cast<LPARAM>(&tool));
}
}
