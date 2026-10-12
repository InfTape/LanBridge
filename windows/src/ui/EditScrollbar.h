#pragma once

#include <windows.h>
#include <algorithm>

namespace ui {
inline void UpdateEditScrollbar(HWND edit) {
    RECT format{};
    SendMessageW(edit,EM_GETRECT,0,reinterpret_cast<LPARAM>(&format));
    if(format.bottom<=format.top) return;
    auto dc=GetDC(edit);
    if(!dc) return;
    auto font=reinterpret_cast<HFONT>(SendMessageW(edit,WM_GETFONT,0,0));
    auto previous=font?SelectObject(dc,font):nullptr;
    TEXTMETRICW metrics{};
    const bool measured=GetTextMetricsW(dc,&metrics)!=FALSE;
    if(previous) SelectObject(dc,previous);
    ReleaseDC(edit,dc);
    if(!measured || metrics.tmHeight<=0) return;
    const int visibleLines=std::max(1,static_cast<int>((format.bottom-format.top)/metrics.tmHeight));
    const bool overflow=SendMessageW(edit,EM_GETLINECOUNT,0,0)>visibleLines;
    const bool shown=(GetWindowLongPtrW(edit,GWL_STYLE)&WS_VSCROLL)!=0;
    if(overflow!=shown) ShowScrollBar(edit,SB_VERT,overflow);
}
}
