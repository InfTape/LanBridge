#pragma once

#include <Windows.h>
#include <limits>

namespace ui {

inline int DpiValue(UINT dpi) noexcept {
    return dpi && dpi<=static_cast<UINT>((std::numeric_limits<int>::max)()) ? static_cast<int>(dpi) : 96;
}

inline int ScaleForDpi(int value, UINT dpi, int base=96) noexcept {
    return MulDiv(value,DpiValue(dpi),base);
}

inline UINT UiDpi(HWND window) {
    using GetWindowDpi = UINT(WINAPI*)(HWND);
    const auto get_dpi = reinterpret_cast<GetWindowDpi>(
        GetProcAddress(GetModuleHandleW(L"user32.dll"), "GetDpiForWindow"));
    if (get_dpi && window) {
        const UINT dpi = get_dpi(window);
        if (dpi) return dpi;
    }
    HDC dc = GetDC(window);
    const int dpi = dc ? GetDeviceCaps(dc, LOGPIXELSX) : 96;
    if (dc) ReleaseDC(window, dc);
    return dpi > 0 ? static_cast<UINT>(dpi) : 96;
}

// Keep application-owned text consistent, including shell-facing popup windows.
inline HFONT CreateUiFont(UINT dpi) {
    LOGFONTW font{};
    font.lfHeight = -ScaleForDpi(9, dpi, 72);
    font.lfWeight = FW_NORMAL;
    font.lfCharSet = DEFAULT_CHARSET;
    font.lfOutPrecision = OUT_OUTLINE_PRECIS;
    font.lfQuality = CLEARTYPE_QUALITY;
    wcscpy_s(font.lfFaceName, L"Microsoft YaHei UI");
    return CreateFontIndirectW(&font);
}

}  // namespace ui
