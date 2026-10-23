#include "FolderPicker.h"
#include "CheckedSize.h"
#include "FolderTransfer.h"
#include "ui/UiTypography.h"
#include <iostream>
#include <limits>
#include <array>

namespace {
void Require(bool value,const char* message) { if(!value) throw std::runtime_error(message); }
template<class Work> void Reject(Work work) {
    bool rejected=false;
    try { work(); } catch(const std::exception&) { rejected=true; }
    Require(rejected,"Failure was silently accepted");
}

// No UI is opened: these implement the real COM interfaces at the API boundary.
class Dialog final : public IFileDialog {
public:
    HRESULT readResult=S_OK,writeResult=S_OK,titleResult=S_OK;
    DWORD options=FOS_DONTADDTORECENT;
    int writes=0,titles=0;
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID,void**) override { return E_NOINTERFACE; }
    ULONG STDMETHODCALLTYPE AddRef() override { return 1; }
    ULONG STDMETHODCALLTYPE Release() override { return 1; }
    HRESULT STDMETHODCALLTYPE Show(HWND) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE SetFileTypes(UINT,const COMDLG_FILTERSPEC*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE SetFileTypeIndex(UINT) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetFileTypeIndex(UINT*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE Advise(IFileDialogEvents*,DWORD*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE Unadvise(DWORD) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE SetOptions(FILEOPENDIALOGOPTIONS flags) override { ++writes; options=flags; return writeResult; }
    HRESULT STDMETHODCALLTYPE GetOptions(FILEOPENDIALOGOPTIONS* flags) override { *flags=options; return readResult; }
    HRESULT STDMETHODCALLTYPE SetDefaultFolder(IShellItem*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE SetFolder(IShellItem*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetFolder(IShellItem**) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetCurrentSelection(IShellItem**) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE SetFileName(LPCWSTR) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetFileName(LPWSTR*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE SetTitle(LPCWSTR) override { ++titles; return titleResult; }
    HRESULT STDMETHODCALLTYPE SetOkButtonLabel(LPCWSTR) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE SetFileNameLabel(LPCWSTR) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetResult(IShellItem**) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE AddPlace(IShellItem*,FDAP) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE SetDefaultExtension(LPCWSTR) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE Close(HRESULT) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE SetClientGuid(REFGUID) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE ClearClientData() override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE SetFilter(IShellItemFilter*) override { return E_NOTIMPL; }
};

int liveItems=0;
class Item final : public IShellItem {
    ULONG references_=1;
    HRESULT nameResult_;
    bool nullPath_;
public:
    Item(HRESULT result,bool nullPath):nameResult_(result),nullPath_(nullPath) { ++liveItems; }
    ~Item() { --liveItems; }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID,void**) override { return E_NOINTERFACE; }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++references_; }
    ULONG STDMETHODCALLTYPE Release() override { const auto count=--references_; if(!count) delete this; return count; }
    HRESULT STDMETHODCALLTYPE BindToHandler(IBindCtx*,REFGUID,REFIID,void**) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetParent(IShellItem**) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetDisplayName(SIGDN,LPWSTR* path) override {
        *path=nullptr;
        if(nullPath_) return nameResult_;
        constexpr wchar_t name[]=L"C:\\folder";
        *path=static_cast<PWSTR>(CoTaskMemAlloc(sizeof(name)));
        if(!*path) return E_OUTOFMEMORY;
        memcpy(*path,name,sizeof(name));
        // Even a failing provider's returned allocation must be freed.
        return nameResult_;
    }
    HRESULT STDMETHODCALLTYPE GetAttributes(SFGAOF,SFGAOF*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE Compare(IShellItem*,SICHINTF,int*) override { return E_NOTIMPL; }
};

class Items final : public IShellItemArray {
public:
    HRESULT countResult=S_OK,nameResult=S_OK;
    DWORD count=2,failAt=MAXDWORD,calls=0;
    bool nullPath=false;
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID,void**) override { return E_NOINTERFACE; }
    ULONG STDMETHODCALLTYPE AddRef() override { return 1; }
    ULONG STDMETHODCALLTYPE Release() override { return 1; }
    HRESULT STDMETHODCALLTYPE BindToHandler(IBindCtx*,REFGUID,REFIID,void**) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetPropertyStore(GETPROPERTYSTOREFLAGS,REFIID,void**) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetPropertyDescriptionList(REFPROPERTYKEY,REFIID,void**) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetAttributes(SIATTRIBFLAGS,SFGAOF,SFGAOF*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetCount(DWORD* value) override { *value=count; return countResult; }
    HRESULT STDMETHODCALLTYPE GetItemAt(DWORD index,IShellItem** item) override {
        ++calls; *item=nullptr;
        if(index==failAt) return E_FAIL;
        *item=new Item(nameResult,nullPath); return S_OK;
    }
    HRESULT STDMETHODCALLTYPE EnumItems(IEnumShellItems**) override { return E_NOTIMPL; }
};

void PickerFailures() {
    Dialog normal;
    folderpicker::Configure(normal);
    constexpr DWORD required=FOS_PICKFOLDERS|FOS_ALLOWMULTISELECT|FOS_FORCEFILESYSTEM|FOS_PATHMUSTEXIST;
    Require((normal.options&required)==required && (normal.options&FOS_DONTADDTORECENT),"Picker flags lost");
    Dialog read; read.readResult=E_FAIL;
    Reject([&]{folderpicker::Configure(read);}); Require(!read.writes && !read.titles,"Continued after GetOptions failure");
    Dialog write; write.writeResult=E_FAIL;
    Reject([&]{folderpicker::Configure(write);}); Require(!write.titles,"Continued after SetOptions failure");
    Dialog title; title.titleResult=E_FAIL;
    Reject([&]{folderpicker::Configure(title);});

    Items normalItems;
    const auto paths=folderpicker::Selection(normalItems);
    Require(paths.size()==2 && paths.front()==L"C:\\folder" && !liveItems,"Selection or COM cleanup failed");
    Items count; count.countResult=E_FAIL;
    Reject([&]{folderpicker::Selection(count);}); Require(!count.calls,"Continued after GetCount failure");
    for(DWORD invalid:std::array<DWORD,3>{0,1025,MAXDWORD}) {
        Items items; items.count=invalid;
        Reject([&]{folderpicker::Selection(items);}); Require(!items.calls,"Invalid count enumerated");
    }
    Items partial; partial.failAt=1;
    Reject([&]{folderpicker::Selection(partial);}); Require(!liveItems,"Partial selection leaked items");
    Items name; name.nameResult=E_FAIL;
    Reject([&]{folderpicker::Selection(name);}); Require(!liveItems,"Path failure leaked item");
    Items empty; empty.nullPath=true;
    Reject([&]{folderpicker::Selection(empty);}); Require(!liveItems,"Null path leaked item");
}

void Apartments() {
    APTTYPE type{}; APTTYPEQUALIFIER qualifier{};
    Require(CoGetApartmentType(&type,&qualifier)==CO_E_NOTINITIALIZED,"Test thread already initialized");
    {
        const win32::Apartment first;
        { const win32::Apartment second; } // S_FALSE must balance its count.
        Require(SUCCEEDED(CoGetApartmentType(&type,&qualifier)),"Nested COM cleanup removed outer apartment");
    }
    Require(CoGetApartmentType(&type,&qualifier)==CO_E_NOTINITIALIZED,"COM initialization count leaked");
    {
        const win32::Apartment mta(COINIT_MULTITHREADED);
        Reject([]{const win32::Apartment sta;}); // RPC_E_CHANGED_MODE owns no count.
        Require(SUCCEEDED(CoGetApartmentType(&type,&qualifier)) && type==APTTYPE_MTA,"Failed initialization released existing COM apartment");
    }
    Require(CoGetApartmentType(&type,&qualifier)==CO_E_NOTINITIALIZED,"MTA count leaked");
}

void Bounds() {
    using bridge::CheckedSize;
    Require(CheckedSize<int>(uint64_t(INT_MAX))==INT_MAX,"Maximum API length rejected");
    Reject([]{CheckedSize<int>(uint64_t(INT_MAX)+1);});
    Reject([]{CheckedSize<DWORD>(uint64_t(MAXDWORD)+1);});
    Reject([]{CheckedSize<size_t>(-1);});
    Require(ui::DpiValue(0)==96 && ui::DpiValue(UINT_MAX)==96 && ui::ScaleForDpi(32,144)==48,"DPI conversion fallback failed");
    for(size_t length=0;length<26;++length) {
        Reject([&]{bridge::folders::DecodeHeader(bridge::Bytes(length));});
    }
    bridge::Bytes overlong(26); overlong[8]=overlong[9]=255;
    Reject([&]{bridge::folders::DecodeHeader(overlong);});
    const auto encoded=bridge::folders::EncodeHeader({1,"safe",0,0,0});
    Require(bridge::folders::DecodeHeader(encoded).name=="safe","Valid header rejected");
}
}

int main() {
    try {
        Apartments(); PickerFailures(); Bounds();
        std::cout<<"PASS: COM S_OK/S_FALSE/failure ownership, atomic folder selection failures, API length and malformed header boundaries\n";
        return 0;
    } catch(const std::exception& e) { std::cerr<<"FAIL: "<<e.what()<<'\n'; return 1; }
}
