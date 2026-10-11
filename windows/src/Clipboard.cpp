#include "Bridge.h"
#include <shlobj.h>
#include <shellapi.h>
#include <cstring>

namespace bridge {
namespace {
bool Open(HWND owner) {
    for(int i=0;i<10;i++) { if(OpenClipboard(owner)) return true; Sleep(30); }
    return false;
}
bool Set(HWND owner, UINT format, HGLOBAL memory) {
    if(!memory) return false;
    if(!Open(owner)) { GlobalFree(memory); return false; }
    bool ok = EmptyClipboard() && SetClipboardData(format, memory);
    CloseClipboard();
    if(!ok) GlobalFree(memory);
    return ok;
}
}
bool CopyText(HWND owner, const std::wstring& text) {
    HGLOBAL memory = GlobalAlloc(GMEM_MOVEABLE, (text.size()+1)*sizeof(wchar_t));
    if(!memory) return false;
    auto p=GlobalLock(memory);
    if(!p) { GlobalFree(memory); return false; }
    memcpy(p,text.c_str(),(text.size()+1)*sizeof(wchar_t));
    GlobalUnlock(memory);
    return Set(owner,CF_UNICODETEXT,memory);
}
bool CopyFiles(HWND owner, const std::vector<std::filesystem::path>& files) {
    if(files.empty()) return false;
    std::wstring names;
    for(const auto& file:files) {
        std::error_code error;
        auto status=std::filesystem::status(file,error);
        if(error || (!std::filesystem::is_regular_file(status) && !std::filesystem::is_directory(status))) return false;
        names += std::filesystem::absolute(file).wstring(); names += L'\0';
    }
    names += L'\0';
    HGLOBAL memory=GlobalAlloc(GMEM_MOVEABLE|GMEM_ZEROINIT,sizeof(DROPFILES)+names.size()*sizeof(wchar_t));
    if(!memory) return false;
    auto* p=static_cast<DROPFILES*>(GlobalLock(memory));
    if(!p) { GlobalFree(memory); return false; }
    p->pFiles=sizeof(DROPFILES); p->fWide=TRUE;
    memcpy(reinterpret_cast<unsigned char*>(p)+sizeof(DROPFILES),names.data(),names.size()*sizeof(wchar_t));
    GlobalUnlock(memory);
    if(!Open(owner)) { GlobalFree(memory); return false; }
    if(!EmptyClipboard() || !SetClipboardData(CF_HDROP,memory)) { CloseClipboard(); GlobalFree(memory); return false; }
    // Explorer and other drop targets should copy instead of moving the received file.
    {
        HGLOBAL effect=GlobalAlloc(GMEM_MOVEABLE,sizeof(DWORD));
        if(effect) {
            if(auto v=static_cast<DWORD*>(GlobalLock(effect))) {
                *v=DROPEFFECT_COPY; GlobalUnlock(effect);
                if(!SetClipboardData(RegisterClipboardFormatW(CFSTR_PREFERREDDROPEFFECT),effect)) GlobalFree(effect);
            } else GlobalFree(effect);
        }
        CloseClipboard();
    }
    return true;
}
std::wstring ReadText(HWND owner) {
    std::wstring result;
    if(Open(owner)) {
        if(auto h=GetClipboardData(CF_UNICODETEXT)) {
            if(auto p=static_cast<const wchar_t*>(GlobalLock(h))) { result=p; GlobalUnlock(h); }
        }
        CloseClipboard();
    }
    return result;
}
std::vector<std::filesystem::path> ReadFiles(HWND owner) {
    std::vector<std::filesystem::path> result;
    if(Open(owner)) {
        if(auto h=static_cast<HDROP>(GetClipboardData(CF_HDROP))) {
            auto n=DragQueryFileW(h,0xffffffff,nullptr,0);
            for(UINT i=0;i<n;i++) {
                std::wstring p(DragQueryFileW(h,i,nullptr,0)+1,L'\0');
                DragQueryFileW(h,i,p.data(),static_cast<UINT>(p.size())); p.pop_back();
                std::error_code error; auto status=std::filesystem::status(p,error);
                if(!error && (std::filesystem::is_regular_file(status) || std::filesystem::is_directory(status))) result.emplace_back(p);
            }
        }
        CloseClipboard();
    }
    return result;
}
}
