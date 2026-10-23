#include "Bridge.h"
#include <shlobj.h>
#include <shellapi.h>
#include <cstring>
#include <gdiplus.h>
#include <stdexcept>

namespace bridge {
namespace {
bool Open(HWND owner) {
    for(int i=0;i<10;i++) { if(OpenClipboard(owner)) return true; Sleep(30); }
    return false;
}
bool Set(HWND owner, UINT format, HGLOBAL memory) {
    if(!memory) return false;
    if(!Open(owner)) { GlobalFree(memory); return false; }
    const bool ok = EmptyClipboard() && SetClipboardData(format, memory);
    CloseClipboard();
    if(!ok) GlobalFree(memory);
    return ok;
}
}
bool CopyText(HWND owner, const std::wstring& text) {
    const HGLOBAL memory = GlobalAlloc(GMEM_MOVEABLE, (text.size()+1)*sizeof(wchar_t));
    if(!memory) return false;
    const auto p=GlobalLock(memory);
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
    const HGLOBAL memory=GlobalAlloc(GMEM_MOVEABLE|GMEM_ZEROINIT,sizeof(DROPFILES)+names.size()*sizeof(wchar_t));
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
            if(const auto v=static_cast<DWORD*>(GlobalLock(effect))) {
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
        if(const auto h=GetClipboardData(CF_UNICODETEXT)) {
            if(const auto p=static_cast<const wchar_t*>(GlobalLock(h))) { result=p; GlobalUnlock(h); }
        }
        CloseClipboard();
    }
    return result;
}
std::vector<std::filesystem::path> ReadFiles(HWND owner) {
    std::vector<std::filesystem::path> result;
    if(Open(owner)) {
        if(const auto h=static_cast<HDROP>(GetClipboardData(CF_HDROP))) {
            const auto n=DragQueryFileW(h,0xffffffff,nullptr,0);
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
std::shared_ptr<const std::filesystem::path> ReadImage(HWND owner) {
    if(!Open(owner)) throw std::runtime_error("剪贴板被占用，请重试");
    struct ClipboardLock {
        ClipboardLock()=default;
        ClipboardLock(const ClipboardLock&)=delete;
        ClipboardLock& operator=(const ClipboardLock&)=delete;
        ClipboardLock(ClipboardLock&&)=delete;
        ClipboardLock& operator=(ClipboardLock&&)=delete;
        ~ClipboardLock() { CloseClipboard(); }
    } lock;
    // Read DIB pixels directly: synthesizing a device-dependent CF_BITMAP can fail.
    if(!IsClipboardFormatAvailable(CF_DIB)) return {};
    const auto memory=GetClipboardData(CF_DIB);
    if(!memory) throw std::runtime_error("无法读取剪贴板图片，请重新复制后重试");
    const auto header=static_cast<BITMAPINFOHEADER*>(GlobalLock(memory));
    if(!header) throw std::runtime_error("无法读取剪贴板图片");
    struct BitmapLock {
        HGLOBAL memory;
        explicit BitmapLock(HGLOBAL value):memory(value) {}
        BitmapLock(const BitmapLock&)=delete;
        BitmapLock& operator=(const BitmapLock&)=delete;
        BitmapLock(BitmapLock&&)=delete;
        BitmapLock& operator=(BitmapLock&&)=delete;
        ~BitmapLock() { GlobalUnlock(memory); }
    } bitmapLock{memory};
    const auto length=GlobalSize(memory);
    const auto invalid=[] { throw std::runtime_error("剪贴板图片格式无效或不受支持，请重新复制"); };
    if(length<sizeof(BITMAPINFOHEADER) || header->biSize<sizeof(BITMAPINFOHEADER) || header->biSize>length) invalid();
    if(header->biWidth<=0 || header->biHeight==0 || header->biPlanes!=1 ||
       (header->biCompression!=BI_RGB && header->biCompression!=BI_BITFIELDS)) invalid();
    const auto depth=header->biBitCount;
    if(depth!=1 && depth!=4 && depth!=8 && depth!=16 && depth!=24 && depth!=32) invalid();
    uint64_t offset=header->biSize;
    if(header->biCompression==BI_BITFIELDS && header->biSize==sizeof(BITMAPINFOHEADER)) offset+=3*sizeof(DWORD);
    const uint64_t colors=header->biClrUsed?header->biClrUsed:(depth<=8?1ULL<<depth:0);
    offset+=colors*sizeof(RGBQUAD);
    const uint64_t stride=((static_cast<uint64_t>(header->biWidth)*depth+31)/32)*4;
    const uint64_t height=header->biHeight<0?-static_cast<int64_t>(header->biHeight):header->biHeight;
    if(offset>length || stride*height>length-offset) invalid();
    struct Imaging {
        ULONG_PTR token=0;
        Imaging() {
            const Gdiplus::GdiplusStartupInput input;
            if(Gdiplus::GdiplusStartup(&token,&input,nullptr)!=Gdiplus::Ok)
                throw std::runtime_error("无法初始化图片编码器");
        }
        Imaging(const Imaging&)=delete;
        Imaging& operator=(const Imaging&)=delete;
        Imaging(Imaging&&)=delete;
        Imaging& operator=(Imaging&&)=delete;
        ~Imaging() { Gdiplus::GdiplusShutdown(token); }
    } imaging;
    Gdiplus::Bitmap image(reinterpret_cast<const BITMAPINFO*>(header),
                         reinterpret_cast<unsigned char*>(header)+offset);
    if(image.GetLastStatus()!=Gdiplus::Ok) throw std::runtime_error("无法读取剪贴板图片");
    UINT count=0,size=0;
    if(Gdiplus::GetImageEncodersSize(&count,&size)!=Gdiplus::Ok || !size)
        throw std::runtime_error("无法获取 PNG 编码器");
    Bytes storage(size); const auto encoders=reinterpret_cast<Gdiplus::ImageCodecInfo*>(storage.data());
    if(Gdiplus::GetImageEncoders(count,size,encoders)!=Gdiplus::Ok)
        throw std::runtime_error("无法获取 PNG 编码器");
    for(UINT i=0;i<count;++i) if(wcscmp(encoders[i].MimeType,L"image/png")==0) {
        auto path=std::shared_ptr<const std::filesystem::path>(
            new std::filesystem::path(std::filesystem::temp_directory_path()/(L"LanBridge-clipboard-"+Wide(RandomCode())+L".png")),
            [](const std::filesystem::path* file) { std::error_code error; std::filesystem::remove(*file,error); delete file; });
        if(image.Save(path->c_str(),&encoders[i].Clsid,nullptr)!=Gdiplus::Ok)
            throw std::runtime_error("无法暂存剪贴板图片，请检查临时目录空间");
        return path;
    }
    throw std::runtime_error("找不到 PNG 编码器");
}
}
