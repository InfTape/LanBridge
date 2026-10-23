#include "ShellTransfer.h"
#include <shobjidl.h>
#include <shlwapi.h>
#include <atomic>
#include <new>

namespace {
// Also declared in context-menu/AppxManifest.xml.
constexpr CLSID CommandId{0x06f3b110,0x144b,0x4cd2,{0x89,0xdb,0xbf,0xae,0x84,0x1e,0x71,0x79}};
std::atomic<long> objects{0};
HMODULE module = nullptr;
std::filesystem::path ModulePath() {
    std::wstring path(32768,L'\0');
    const DWORD length = GetModuleFileNameW(module,path.data(),static_cast<DWORD>(path.size()));
    if(!length || length >= path.size()) throw std::runtime_error("Cannot find shell extension");
    path.resize(length); return path;
}
class Command final : public IExplorerCommand {
    std::atomic<ULONG> references_{1};
public:
    Command() { ++objects; }
    Command(const Command&)=delete;
    Command& operator=(const Command&)=delete;
    Command(Command&&)=delete;
    Command& operator=(Command&&)=delete;
    ~Command() { --objects; }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** value) override {
        if(!value) return E_POINTER; *value = nullptr;
        if(iid == IID_IUnknown || iid == IID_IExplorerCommand) *value = static_cast<IExplorerCommand*>(this);
        if(!*value) return E_NOINTERFACE; AddRef(); return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++references_; }
    ULONG STDMETHODCALLTYPE Release() override { const auto n = --references_; if(!n) delete this; return n; }
    HRESULT STDMETHODCALLTYPE GetTitle(IShellItemArray*, PWSTR* title) override { return SHStrDupW(L"发送到手机",title); }
    HRESULT STDMETHODCALLTYPE GetIcon(IShellItemArray*, PWSTR* icon) override {
        if(!icon) return E_POINTER; *icon = nullptr;
        try { return SHStrDupW((ModulePath().wstring()+L",-101").c_str(),icon); }
        catch(...) { return E_FAIL; }
    }
    HRESULT STDMETHODCALLTYPE GetToolTip(IShellItemArray*, PWSTR* tooltip) override { return SHStrDupW(L"通过 LanBridge 发送所选文件或文件夹到已配对的手机",tooltip); }
    HRESULT STDMETHODCALLTYPE GetCanonicalName(GUID* name) override { if(!name) return E_POINTER; *name = CommandId; return S_OK; }
    HRESULT STDMETHODCALLTYPE GetState(IShellItemArray* items, BOOL, EXPCMDSTATE* state) override {
        if(!state) return E_POINTER; *state = ECS_HIDDEN;
        if(!items) return S_OK;
        DWORD count = 0; if(FAILED(items->GetCount(&count)) || !count || count > shelltransfer::MaxFiles) return S_OK;
        SFGAOF attributes = 0;
        // Only real filesystem files can be sent. ZIP archives also have
        // SFGAO_FOLDER, so determine directory status from the Win32 path.
        if(FAILED(items->GetAttributes(SIATTRIBFLAGS_AND,SFGAO_FILESYSTEM,&attributes)) || !(attributes & SFGAO_FILESYSTEM)) return S_OK;
        for(DWORD i=0;i<count;i++) {
            IShellItem* item = nullptr;
            if(FAILED(items->GetItemAt(i,&item))) return S_OK;
            PWSTR path = nullptr; const auto result = item->GetDisplayName(SIGDN_FILESYSPATH,&path); item->Release();
            const DWORD fileAttributes = SUCCEEDED(result) && path ? GetFileAttributesW(path) : INVALID_FILE_ATTRIBUTES;
            CoTaskMemFree(path);
            if(fileAttributes == INVALID_FILE_ATTRIBUTES || (fileAttributes & FILE_ATTRIBUTE_REPARSE_POINT)) return S_OK;
        }
        *state = ECS_ENABLED; return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Invoke(IShellItemArray* items, IBindCtx*) override {
        if(!items) return E_INVALIDARG;
        try {
            EXPCMDSTATE state=ECS_HIDDEN;
        const auto stateResult=GetState(items,FALSE,&state);
        if(FAILED(stateResult)) return stateResult;
            if(state != ECS_ENABLED) return E_INVALIDARG;
            DWORD count = 0; if(FAILED(items->GetCount(&count))) return E_FAIL;
            shelltransfer::Paths paths; paths.reserve(count);
            for(DWORD i=0;i<count;i++) {
                IShellItem* item = nullptr; auto result = items->GetItemAt(i,&item);
                if(FAILED(result)) return result;
                PWSTR path = nullptr; result = item->GetDisplayName(SIGDN_FILESYSPATH,&path); item->Release();
                if(FAILED(result)) return result;
                try { paths.emplace_back(path); } catch(...) { CoTaskMemFree(path); throw; }
                CoTaskMemFree(path);
            }
            shelltransfer::Launch(ModulePath().parent_path()/L"LanBridge.exe",paths);
            return S_OK;
        } catch(const std::bad_alloc&) { return E_OUTOFMEMORY; }
        catch(...) { return E_FAIL; }
    }
    HRESULT STDMETHODCALLTYPE GetFlags(EXPCMDFLAGS* flags) override { if(!flags) return E_POINTER; *flags = ECF_DEFAULT; return S_OK; }
    HRESULT STDMETHODCALLTYPE EnumSubCommands(IEnumExplorerCommand** commands) override { if(!commands) return E_POINTER; *commands = nullptr; return E_NOTIMPL; }
};
class Factory final : public IClassFactory {
    std::atomic<ULONG> references_{1};
public:
    Factory() { ++objects; }
    Factory(const Factory&)=delete;
    Factory& operator=(const Factory&)=delete;
    Factory(Factory&&)=delete;
    Factory& operator=(Factory&&)=delete;
    ~Factory() { --objects; }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** value) override {
        if(!value) return E_POINTER; *value = nullptr;
        if(iid == IID_IUnknown || iid == IID_IClassFactory) *value = static_cast<IClassFactory*>(this);
        if(!*value) return E_NOINTERFACE; AddRef(); return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++references_; }
    ULONG STDMETHODCALLTYPE Release() override { const auto n = --references_; if(!n) delete this; return n; }
    HRESULT STDMETHODCALLTYPE CreateInstance(IUnknown* outer, REFIID iid, void** value) override {
        if(!value) return E_POINTER; *value = nullptr; if(outer) return CLASS_E_NOAGGREGATION;
        auto* command = new(std::nothrow) Command; if(!command) return E_OUTOFMEMORY;
        const auto result = command->QueryInterface(iid,value); command->Release(); return result;
    }
    HRESULT STDMETHODCALLTYPE LockServer(BOOL lock) override { if(lock) ++objects; else --objects; return S_OK; }
};
}
BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID) {
    if(reason == DLL_PROCESS_ATTACH) { module = instance; DisableThreadLibraryCalls(instance); }
    return TRUE;
}
extern "C" HRESULT __stdcall DllCanUnloadNow() { return objects == 0 ? S_OK : S_FALSE; }
extern "C" HRESULT __stdcall DllGetClassObject(REFCLSID clsid, REFIID iid, void** value) {
    if(!value) return E_POINTER; *value = nullptr;
    if(clsid != CommandId) return CLASS_E_CLASSNOTAVAILABLE;
    auto* factory = new(std::nothrow) Factory; if(!factory) return E_OUTOFMEMORY;
    const auto result = factory->QueryInterface(iid,value); factory->Release(); return result;
}
