#pragma once

#include <Windows.h>

namespace ui {

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
inline HFONT CreateUiFont(UINT dpi, int height = 14, int weight = FW_NORMAL) {
    LOGFONTW font{};
    font.lfHeight = -MulDiv(height, dpi, 96);
    font.lfWeight = weight;
    font.lfCharSet = DEFAULT_CHARSET;
    font.lfOutPrecision = OUT_OUTLINE_PRECIS;
    font.lfQuality = CLEARTYPE_QUALITY;
    wcscpy_s(font.lfFaceName, L"Microsoft YaHei UI");
    return CreateFontIndirectW(&font);
}

}  // namespace ui
