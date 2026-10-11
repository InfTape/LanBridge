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
        TOOLINFOW tool{sizeof(tool)}; tool.uFlags=TTF_TRACK|TTF_ABSOLUTE; tool.hwnd=owner; tool.uId=1;
        tool.lpszText=const_cast<wchar_t*>(L"LanBridge · 局域网互传");
        if(!SendMessageW(tooltip_,TTM_ADDTOOLW,0,reinterpret_cast<LPARAM>(&tool))) {
            DestroyWindow(tooltip_); tooltip_=nullptr; return false;
        }
    }
    SetWindowPos(tooltip_,HWND_TOPMOST,anchor.x,anchor.y,0,0,SWP_NOSIZE|SWP_NOACTIVATE);
    UINT dpi=UiDpi(tooltip_);
    if(!font_ || dpi!=dpi_) {
        HFONT replacement=CreateUiFont(dpi); if(!replacement) return false;
        auto previous=font_; font_=replacement; dpi_=dpi;
        SendMessageW(tooltip_,WM_SETFONT,reinterpret_cast<WPARAM>(font_),TRUE);
        if(previous) DeleteObject(previous);
    }
    return true;
}
UINT PopupMenu::Track(HMENU menu,HWND owner,const POINT& anchor) {
    HideTooltip(); if(!Prepare(owner,anchor)) return 0;
    int count=GetMenuItemCount(menu); if(count<0) return 0;
    labels_.clear(); labels_.resize(count);
    for(int i=0;i<count;i++) {
        MENUITEMINFOW item{sizeof(item)}; item.fMask=MIIM_FTYPE|MIIM_STRING;
        if(!GetMenuItemInfoW(menu,i,TRUE,&item) || (item.fType&MFT_SEPARATOR)) continue;
        auto& label=labels_[i]; label.resize(item.cch+1); item.dwTypeData=label.data(); item.cch=static_cast<UINT>(label.size());
        if(!GetMenuItemInfoW(menu,i,TRUE,&item)) continue;
        label.resize(wcslen(label.c_str()));
        item.fMask=MIIM_FTYPE|MIIM_DATA|MIIM_STRING; item.fType|=MFT_OWNERDRAW;
        item.dwItemData=reinterpret_cast<ULONG_PTR>(label.c_str()); item.dwTypeData=label.data();
        SetMenuItemInfoW(menu,i,TRUE,&item);
    }
    MENUINFO info{sizeof(info)}; info.fMask=MIM_STYLE;
    if(GetMenuInfo(menu,&info)) { info.dwStyle|=MNS_NOCHECK; SetMenuInfo(menu,&info); }
    SetForegroundWindow(owner); open_=true;
    UINT command=TrackPopupMenu(menu,TPM_RIGHTBUTTON|TPM_RETURNCMD,anchor.x,anchor.y,0,owner,nullptr);
    open_=false; PostMessageW(owner,WM_NULL,0,0); return command;
}
bool PopupMenu::Measure(MEASUREITEMSTRUCT& item) const {
    if(item.CtlType!=ODT_MENU || !item.itemData || !font_) return false;
    auto text=reinterpret_cast<const wchar_t*>(item.itemData); HDC dc=GetDC(owner_); if(!dc) return false;
    auto previous=SelectObject(dc,font_); SIZE size{}; GetTextExtentPoint32W(dc,text,static_cast<int>(wcslen(text)),&size);
    SelectObject(dc,previous); ReleaseDC(owner_,dc);
    item.itemWidth=size.cx+MulDiv(52,dpi_,96);
    item.itemHeight=std::max(static_cast<int>(size.cy)+MulDiv(10,dpi_,96),MulDiv(32,dpi_,96)); return true;
}
bool PopupMenu::Draw(const DRAWITEMSTRUCT& item) const {
    if(item.CtlType!=ODT_MENU || !item.itemData || !font_) return false;
    int saved=SaveDC(item.hDC);
    bool selected=(item.itemState&ODS_SELECTED)!=0,disabled=(item.itemState&(ODS_DISABLED|ODS_GRAYED))!=0;
    FillRect(item.hDC,&item.rcItem,GetSysColorBrush(selected?COLOR_HIGHLIGHT:COLOR_MENU));
    COLORREF color=GetSysColor(disabled?COLOR_GRAYTEXT:selected?COLOR_HIGHLIGHTTEXT:COLOR_MENUTEXT);
    SelectObject(item.hDC,font_); SetBkMode(item.hDC,TRANSPARENT); SetTextColor(item.hDC,color);
    RECT text=item.rcItem; text.left+=MulDiv(32,dpi_,96); text.right-=MulDiv(12,dpi_,96);
    DrawTextW(item.hDC,reinterpret_cast<const wchar_t*>(item.itemData),-1,&text,DT_SINGLELINE|DT_VCENTER|DT_NOPREFIX);
    if(item.itemState&ODS_CHECKED) {
        int x=item.rcItem.left+MulDiv(10,dpi_,96),y=(item.rcItem.top+item.rcItem.bottom)/2;
        HPEN pen=CreatePen(PS_SOLID,std::max(1,MulDiv(2,dpi_,96)),color); auto old=SelectObject(item.hDC,pen);
        MoveToEx(item.hDC,x,y,nullptr); LineTo(item.hDC,x+MulDiv(4,dpi_,96),y+MulDiv(4,dpi_,96)); LineTo(item.hDC,x+MulDiv(11,dpi_,96),y-MulDiv(4,dpi_,96));
        SelectObject(item.hDC,old); DeleteObject(pen);
    }
    RestoreDC(item.hDC,saved); return true;
}
void PopupMenu::ShowTooltip(HWND owner,const POINT& anchor) {
    if(open_ || !Prepare(owner,anchor)) return;
    NOTIFYICONIDENTIFIER icon{sizeof(icon)}; icon.hWnd=owner; icon.uID=1; RECT iconRect{};
    if(FAILED(Shell_NotifyIconGetRect(&icon,&iconRect))) iconRect={anchor.x,anchor.y,anchor.x,anchor.y};
    int padding=MulDiv(8,dpi_,96); RECT margin{padding,padding/2,padding,padding/2};
    SendMessageW(tooltip_,TTM_SETMARGIN,0,reinterpret_cast<LPARAM>(&margin));
    HDC dc=GetDC(tooltip_); auto old=SelectObject(dc,font_); SIZE size{}; constexpr wchar_t tip[]=L"LanBridge · 局域网互传";
    GetTextExtentPoint32W(dc,tip,static_cast<int>(std::size(tip)-1),&size); SelectObject(dc,old); ReleaseDC(tooltip_,dc);
    int width=size.cx+2*padding+MulDiv(6,dpi_,96),height=size.cy+padding+MulDiv(6,dpi_,96);
    MONITORINFO monitor{sizeof(monitor)}; GetMonitorInfoW(MonitorFromPoint(anchor,MONITOR_DEFAULTTONEAREST),&monitor);
    int x=std::clamp(static_cast<int>(iconRect.left),static_cast<int>(monitor.rcWork.left),std::max(static_cast<int>(monitor.rcWork.left),static_cast<int>(monitor.rcWork.right)-width));
    int y=iconRect.top-height-MulDiv(4,dpi_,96); if(y<monitor.rcWork.top) y=iconRect.bottom+MulDiv(4,dpi_,96);
    y=std::clamp(y,static_cast<int>(monitor.rcWork.top),std::max(static_cast<int>(monitor.rcWork.top),static_cast<int>(monitor.rcWork.bottom)-height));
    TOOLINFOW tool{sizeof(tool)}; tool.hwnd=owner; tool.uId=1;
    SendMessageW(tooltip_,TTM_TRACKPOSITION,0,MAKELPARAM(x,y)); SendMessageW(tooltip_,TTM_TRACKACTIVATE,TRUE,reinterpret_cast<LPARAM>(&tool));
}
void PopupMenu::HideTooltip() {
    if(!tooltip_ || !IsWindow(tooltip_)) return;
    TOOLINFOW tool{sizeof(tool)}; tool.hwnd=owner_; tool.uId=1;
    SendMessageW(tooltip_,TTM_TRACKACTIVATE,FALSE,reinterpret_cast<LPARAM>(&tool));
}
}
