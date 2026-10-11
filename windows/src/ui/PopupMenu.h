#pragma once
#include <windows.h>
#include <string>
#include <vector>

namespace ui {
// Application-owned tray menu and tooltip, following native-screenshot-tool.
class PopupMenu {
public:
    ~PopupMenu();
    bool Prepare(HWND owner, const POINT& anchor);
    UINT Track(HMENU menu, HWND owner, const POINT& anchor);
    bool Measure(MEASUREITEMSTRUCT& item) const;
    bool Draw(const DRAWITEMSTRUCT& item) const;
    void ShowTooltip(HWND owner, const POINT& anchor);
    void HideTooltip();
    HFONT Font() const { return font_; }
    UINT Dpi() const { return dpi_; }
private:
    HWND owner_=nullptr,tooltip_=nullptr;
    HFONT font_=nullptr;
    UINT dpi_=96;
    bool open_=false;
    std::vector<std::wstring> labels_;
};
}
