#pragma once
#include "ShellTransfer.h"
#include "Win32Support.h"
#include <shobjidl.h>
#include <wrl/client.h>

namespace folderpicker {
inline void Configure(IFileDialog& dialog) {
    DWORD flags=0;
    win32::Check(dialog.GetOptions(&flags),"Cannot read folder picker options");
    win32::Check(dialog.SetOptions(flags|FOS_PICKFOLDERS|FOS_ALLOWMULTISELECT|
        FOS_FORCEFILESYSTEM|FOS_PATHMUSTEXIST|FOS_NOCHANGEDIR),"Cannot configure folder picker");
    win32::Check(dialog.SetTitle(L"选择要发送到手机的文件夹"),"Cannot set folder picker title");
}

// Build a complete selection before sending anything. A failed item must not
// silently turn a multiple-folder selection into a partial send.
inline shelltransfer::Paths Selection(IShellItemArray& items) {
    DWORD count=0;
    win32::Check(items.GetCount(&count),"Cannot read selected folder count");
    if(!count || count>shelltransfer::MaxFiles) throw std::runtime_error("Select 1 to 1024 folders");
    shelltransfer::Paths paths;
    paths.reserve(count);
    for(DWORD i=0;i<count;++i) {
        Microsoft::WRL::ComPtr<IShellItem> item;
        win32::Check(items.GetItemAt(i,&item),"Cannot read selected folder");
        PWSTR raw=nullptr;
        const auto result=item->GetDisplayName(SIGDN_FILESYSPATH,&raw);
        const win32::TaskString path(raw);
        win32::Check(result,"Cannot read selected folder path");
        if(!path) throw std::runtime_error("Selected folder has no filesystem path");
        paths.emplace_back(path.get());
    }
    return paths;
}
}
